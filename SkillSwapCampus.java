import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import java.util.concurrent.*;

/**
 * SkillSwap Campus - peer skill-swap platform.
 * Frontend: Java Swing.   Backend: PostgreSQL (via JDBC).
 *
 * Compile:  javac SkillSwapCampus.java
 * Run:      java -cp ".:postgresql-42.7.4.jar" SkillSwapCampus        (Windows: use ";" instead of ":")
 *
 * On first start a "Connect to PostgreSQL" dialog appears. The database and all tables are created automatically.
 * Demo accounts (password "demo"): ramos, alamag, henry, jeb, chinu, dino, jamesa, kylet. Or create your own.
 * Every computer that points at the same PostgreSQL server shares accounts, posts, bookings and chat.
 */
public class SkillSwapCampus {

    // ====================== MODEL (loaded from / saved to PostgreSQL) ======================
    static class User {
        String username, realName, email, passHash, bio = "";
        boolean useRealName, publicRatings = true, nearby, demo;
        List<String> teach = new ArrayList<>(), learn = new ArrayList<>();
        int credits = 5;
    }
    static class Post { int id; String author, type, skill, category, desc; long time; Set<String> likes = new HashSet<>(); }
    static class Sess {
        int id, rating; String teacher, learner, skill, mode, status, requester, review = "";
        LocalDateTime at;
    }
    static class Msg { int id; String from, to, text; long time; }
    static class Report { int id; String by, target, reason, details; long time; }
    static class Store {
        Map<String, User> users = new LinkedHashMap<>();
        List<Post> posts = new ArrayList<>(); List<Sess> sessions = new ArrayList<>();
        List<Msg> msgs = new ArrayList<>(); List<Report> reports = new ArrayList<>();
        String lastSync = "never";
    }
    static class Match { User u; List<String> theyTeach, iTeach; }

    static final int MAX_CREDITS = 10;
    static final Path DIR = Paths.get(System.getProperty("user.home"), ".skillswap");
    static final Path CFG = DIR.resolve("config.properties");
    static final Properties cfg = new Properties();
    static Store S; static User me;
    static long lastMsgSeen;
    static final Set<Integer> remindedIds = new HashSet<>();

    // ====================== POSTGRESQL LAYER ======================
    static Connection db, dbRead;                 // db = writes (EDT), dbRead = snapshot reads (background poller)
    static final Object RL = new Object();
    static volatile int ver;                      // bumped on every local save, used to discard stale polls
    static int busy, localId = 1;                 // busy > 0 while a modal dialog is open (don't swap data underneath it)
    static Map<String, String> snap = new HashMap<>();   // row fingerprints as last seen in the database
    static ScheduledExecutorService poller;

    static final String[] DDL = {
            "CREATE SEQUENCE IF NOT EXISTS ss_id_seq",
            "CREATE TABLE IF NOT EXISTS users(ord BIGSERIAL, username TEXT PRIMARY KEY, real_name TEXT NOT NULL DEFAULT '', email TEXT NOT NULL UNIQUE,"
                    + " pass_hash TEXT NOT NULL, bio TEXT NOT NULL DEFAULT '', use_real_name BOOLEAN NOT NULL DEFAULT FALSE, public_ratings BOOLEAN NOT NULL DEFAULT TRUE,"
                    + " nearby BOOLEAN NOT NULL DEFAULT FALSE, demo BOOLEAN NOT NULL DEFAULT FALSE, credits INT NOT NULL DEFAULT 5)",
            "CREATE TABLE IF NOT EXISTS user_skills(ord BIGSERIAL, username TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,"
                    + " kind CHAR(1) NOT NULL, skill TEXT NOT NULL, PRIMARY KEY(username, kind, skill))",
            "CREATE TABLE IF NOT EXISTS posts(id INT PRIMARY KEY, author TEXT NOT NULL, post_type TEXT NOT NULL, skill TEXT NOT NULL,"
                    + " category TEXT NOT NULL, descr TEXT NOT NULL, created BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS post_likes(post_id INT NOT NULL REFERENCES posts(id) ON DELETE CASCADE, username TEXT NOT NULL, PRIMARY KEY(post_id, username))",
            "CREATE TABLE IF NOT EXISTS sessions(id INT PRIMARY KEY, teacher TEXT NOT NULL, learner TEXT NOT NULL, skill TEXT NOT NULL, sess_mode TEXT NOT NULL,"
                    + " status TEXT NOT NULL, requester TEXT NOT NULL, review TEXT NOT NULL DEFAULT '', rating INT NOT NULL DEFAULT 0, starts_at TIMESTAMP NOT NULL)",
            "CREATE TABLE IF NOT EXISTS messages(id INT PRIMARY KEY, from_user TEXT NOT NULL, to_user TEXT NOT NULL, body TEXT NOT NULL, created BIGINT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_messages_pair ON messages(from_user, to_user, created)",
            "CREATE TABLE IF NOT EXISTS reports(id INT PRIMARY KEY, by_user TEXT NOT NULL, target TEXT NOT NULL, reason TEXT NOT NULL,"
                    + " details TEXT NOT NULL DEFAULT '', created BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)"
    };

    static String c(String k, String d) { return cfg.getProperty(k, d); }
    static void loadCfg() { try (InputStream in = Files.newInputStream(CFG)) { cfg.load(in); } catch (Exception e) { /* first run */ } }
    static void saveCfg() { try { Files.createDirectories(DIR); try (OutputStream o = Files.newOutputStream(CFG)) { cfg.store(o, "SkillSwap Campus settings"); } } catch (Exception e) { } }
    static String nz(String s) { return s == null ? "" : s; }
    static String j(Object... a) { return Arrays.stream(a).map(String::valueOf).collect(Collectors.joining("\u0001")); }

    static Connection open(String host, String port, String name, String user, String pw) throws SQLException {
        String base = "jdbc:postgresql://" + host + ":" + port + "/";
        try { return DriverManager.getConnection(base + name, user, pw); }
        catch (SQLException e) {
            if (!"3D000".equals(e.getSQLState()) || !name.matches("\\w+")) throw e;     // 3D000 = database does not exist yet -> create it
            try (Connection a = DriverManager.getConnection(base + "postgres", user, pw); Statement s = a.createStatement()) { s.execute("CREATE DATABASE " + name); }
            return DriverManager.getConnection(base + name, user, pw);
        }
    }
    static void closeDb() {
        try { if (db != null) db.close(); } catch (Exception e) { } try { if (dbRead != null) dbRead.close(); } catch (Exception e) { } db = null; dbRead = null;
    }
    static boolean connectLoop(String err) {
        try { Class.forName("org.postgresql.Driver"); }
        catch (ClassNotFoundException e) {
            info("PostgreSQL JDBC driver not found.\n\nDownload postgresql-42.x.jar from jdbc.postgresql.org and start the app with:\n"
                    + "  java -cp \".:postgresql-42.7.4.jar\" SkillSwapCampus      (Mac/Linux)\n  java -cp \".;postgresql-42.7.4.jar\" SkillSwapCampus      (Windows)");
            return false;
        }
        while (true) {
            JTextField h = field(), po = field(), dn = field(), us = field(); JPasswordField pw = pass(); JCheckBox rem = check("Remember password on this computer", false);
            h.setText(c("db.host", "localhost")); po.setText(c("db.port", "5432")); dn.setText(c("db.name", "skillswap")); us.setText(c("db.user", "postgres"));
            pw.setText(c("db.password", System.getenv().getOrDefault("SKILLSWAP_DB_PASSWORD", ""))); rem.setSelected(!c("db.password", "").isEmpty());
            JPanel f = form(new String[]{"Host", "Port", "Database", "User", "Password", ""}, new JComponent[]{h, po, dn, us, pw, rem}); f.setPreferredSize(new Dimension(380, 230));
            JPanel wrap = new JPanel(new BorderLayout(0, 8)); wrap.setBackground(PANEL);
            if (!err.isEmpty()) wrap.add(lbl("<html><body style='width:340px'>" + esc(err) + "</body></html>", Font.PLAIN, 11, PINK), BorderLayout.NORTH);
            wrap.add(f);
            if (!ask("Connect to PostgreSQL", wrap)) return false;
            String host = h.getText().trim(), port = po.getText().trim(), name = dn.getText().trim().toLowerCase(), user = us.getText().trim(), pass = new String(pw.getPassword());
            Connection a = null, b = null;
            try {
                a = open(host, port, name, user, pass); schema(a); b = open(host, port, name, user, pass);
                b.setAutoCommit(false); b.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                closeDb(); db = a; dbRead = b;
                cfg.setProperty("db.host", host); cfg.setProperty("db.port", port); cfg.setProperty("db.name", name); cfg.setProperty("db.user", user);
                if (rem.isSelected()) cfg.setProperty("db.password", pass); else cfg.remove("db.password");
                saveCfg(); return true;
            } catch (Exception ex) {
                try { if (a != null) a.close(); if (b != null) b.close(); } catch (Exception x) { }
                err = "Could not connect: " + ex.getMessage();
            }
        }
    }
    static void schema(Connection cn) throws SQLException { try (Statement s = cn.createStatement()) { for (String q : DDL) s.execute(q); } }
    static int nextId() {
        if (db == null) return localId++;
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT nextval('ss_id_seq')")) { r.next(); return r.getInt(1); }
        catch (SQLException e) { throw new IllegalStateException("Database error: " + e.getMessage(), e); }
    }

    static Store fetch() throws SQLException {
        synchronized (RL) {
            Store st = new Store();
            try (Statement s = dbRead.createStatement()) {
                try (ResultSet r = s.executeQuery("SELECT username,real_name,email,pass_hash,bio,use_real_name,public_ratings,nearby,demo,credits FROM users ORDER BY ord")) {
                    while (r.next()) { User u = new User(); u.username = r.getString(1); u.realName = nz(r.getString(2)); u.email = r.getString(3); u.passHash = r.getString(4); u.bio = nz(r.getString(5));
                        u.useRealName = r.getBoolean(6); u.publicRatings = r.getBoolean(7); u.nearby = r.getBoolean(8); u.demo = r.getBoolean(9); u.credits = r.getInt(10); st.users.put(u.username, u); } }
                try (ResultSet r = s.executeQuery("SELECT username,kind,skill FROM user_skills ORDER BY ord")) {
                    while (r.next()) { User u = st.users.get(r.getString(1)); if (u != null) (r.getString(2).equals("T") ? u.teach : u.learn).add(r.getString(3)); } }
                try (ResultSet r = s.executeQuery("SELECT id,author,post_type,skill,category,descr,created FROM posts ORDER BY id")) {
                    while (r.next()) { Post p = new Post(); p.id = r.getInt(1); p.author = r.getString(2); p.type = r.getString(3); p.skill = r.getString(4); p.category = r.getString(5); p.desc = r.getString(6); p.time = r.getLong(7); st.posts.add(p); } }
                Map<Integer, Post> pm = new HashMap<>(); for (Post p : st.posts) pm.put(p.id, p);
                try (ResultSet r = s.executeQuery("SELECT post_id,username FROM post_likes")) { while (r.next()) { Post p = pm.get(r.getInt(1)); if (p != null) p.likes.add(r.getString(2)); } }
                try (ResultSet r = s.executeQuery("SELECT id,teacher,learner,skill,sess_mode,status,requester,review,rating,starts_at FROM sessions ORDER BY id")) {
                    while (r.next()) { Sess x = new Sess(); x.id = r.getInt(1); x.teacher = r.getString(2); x.learner = r.getString(3); x.skill = r.getString(4); x.mode = r.getString(5); x.status = r.getString(6);
                        x.requester = r.getString(7); x.review = nz(r.getString(8)); x.rating = r.getInt(9); x.at = r.getTimestamp(10).toLocalDateTime(); st.sessions.add(x); } }
                try (ResultSet r = s.executeQuery("SELECT id,from_user,to_user,body,created FROM messages ORDER BY created,id")) {
                    while (r.next()) { Msg m = new Msg(); m.id = r.getInt(1); m.from = r.getString(2); m.to = r.getString(3); m.text = r.getString(4); m.time = r.getLong(5); st.msgs.add(m); } }
                try (ResultSet r = s.executeQuery("SELECT id,by_user,target,reason,details,created FROM reports ORDER BY id")) {
                    while (r.next()) { Report x = new Report(); x.id = r.getInt(1); x.by = r.getString(2); x.target = r.getString(3); x.reason = r.getString(4); x.details = nz(r.getString(5)); x.time = r.getLong(6); st.reports.add(x); } }
                try (ResultSet r = s.executeQuery("SELECT v FROM meta WHERE k='last_sync'")) { if (r.next()) st.lastSync = r.getString(1); }
                dbRead.commit();
            } catch (SQLException e) { try { dbRead.rollback(); } catch (Exception x) { } throw e; }
            return st;
        }
    }
    /** One short fingerprint per row; save() only writes rows whose fingerprint changed, so two clients rarely overwrite each other. */
    static Map<String, String> fingerprints(Store st) {
        Map<String, String> m = new HashMap<>();
        for (User u : st.users.values()) m.put("u:" + u.username, j(nz(u.realName), u.email, u.passHash, nz(u.bio), u.useRealName, u.publicRatings, u.nearby, u.demo, u.credits, String.join("\u0002", u.teach), String.join("\u0002", u.learn)));
        for (Post p : st.posts) m.put("p:" + p.id, j(p.author, p.type, p.skill, p.category, p.desc, p.time, new TreeSet<>(p.likes)));
        for (Sess s : st.sessions) m.put("s:" + s.id, j(s.teacher, s.learner, s.skill, s.mode, s.status, s.requester, nz(s.review), s.rating, s.at.truncatedTo(ChronoUnit.SECONDS)));
        for (Msg x : st.msgs) m.put("m:" + x.id, "");
        for (Report x : st.reports) m.put("r:" + x.id, "");
        m.put("meta:sync", st.lastSync);
        return m;
    }
    static boolean chg(String k, Map<String, String> now) { return !now.get(k).equals(snap.get(k)); }
    static void save() {
        if (db == null) return;
        ver++; Map<String, String> now = fingerprints(S);
        try {
            db.setAutoCommit(false);
            try {
                for (User u : S.users.values()) if (chg("u:" + u.username, now)) writeUser(u);
                for (Post p : S.posts) if (chg("p:" + p.id, now)) writePost(p);
                for (Sess s : S.sessions) if (chg("s:" + s.id, now)) writeSess(s);
                for (Msg m : S.msgs) if (chg("m:" + m.id, now)) writeMsg(m);
                for (Report r : S.reports) if (chg("r:" + r.id, now)) writeReport(r);
                if (chg("meta:sync", now)) try (PreparedStatement p = db.prepareStatement("INSERT INTO meta(k,v) VALUES('last_sync',?) ON CONFLICT(k) DO UPDATE SET v=EXCLUDED.v")) { p.setString(1, S.lastSync); p.executeUpdate(); }
                for (String k : snap.keySet()) if (k.startsWith("p:") && !now.containsKey(k))
                    try (PreparedStatement p = db.prepareStatement("DELETE FROM posts WHERE id=?")) { p.setInt(1, Integer.parseInt(k.substring(2))); p.executeUpdate(); }
                db.commit(); snap = now;
            } catch (SQLException e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        } catch (SQLException e) { toast("Database error: " + nz(e.getMessage()).split("\n")[0]); }
    }
    static void writeUser(User u) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO users(username,real_name,email,pass_hash,bio,use_real_name,public_ratings,nearby,demo,credits) VALUES(?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(username) DO UPDATE SET real_name=EXCLUDED.real_name,email=EXCLUDED.email,pass_hash=EXCLUDED.pass_hash,bio=EXCLUDED.bio,use_real_name=EXCLUDED.use_real_name,"
                + "public_ratings=EXCLUDED.public_ratings,nearby=EXCLUDED.nearby,demo=EXCLUDED.demo,credits=EXCLUDED.credits")) {
            p.setString(1, u.username); p.setString(2, nz(u.realName)); p.setString(3, u.email); p.setString(4, u.passHash); p.setString(5, nz(u.bio)); p.setBoolean(6, u.useRealName);
            p.setBoolean(7, u.publicRatings); p.setBoolean(8, u.nearby); p.setBoolean(9, u.demo); p.setInt(10, u.credits); p.executeUpdate();
        }
        try (PreparedStatement d = db.prepareStatement("DELETE FROM user_skills WHERE username=?")) { d.setString(1, u.username); d.executeUpdate(); }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO user_skills(username,kind,skill) VALUES(?,?,?) ON CONFLICT DO NOTHING")) {
            for (String s : u.teach) { p.setString(1, u.username); p.setString(2, "T"); p.setString(3, s); p.addBatch(); }
            for (String s : u.learn) { p.setString(1, u.username); p.setString(2, "L"); p.setString(3, s); p.addBatch(); }
            p.executeBatch();
        }
    }
    static void writePost(Post po) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO posts(id,author,post_type,skill,category,descr,created) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                + "author=EXCLUDED.author,post_type=EXCLUDED.post_type,skill=EXCLUDED.skill,category=EXCLUDED.category,descr=EXCLUDED.descr,created=EXCLUDED.created")) {
            p.setInt(1, po.id); p.setString(2, po.author); p.setString(3, po.type); p.setString(4, po.skill); p.setString(5, po.category); p.setString(6, po.desc); p.setLong(7, po.time); p.executeUpdate();
        }
        try (PreparedStatement d = db.prepareStatement("DELETE FROM post_likes WHERE post_id=?")) { d.setInt(1, po.id); d.executeUpdate(); }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO post_likes(post_id,username) VALUES(?,?) ON CONFLICT DO NOTHING")) {
            for (String l : po.likes) { p.setInt(1, po.id); p.setString(2, l); p.addBatch(); } p.executeBatch();
        }
    }
    static void writeSess(Sess s) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO sessions(id,teacher,learner,skill,sess_mode,status,requester,review,rating,starts_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                + "teacher=EXCLUDED.teacher,learner=EXCLUDED.learner,skill=EXCLUDED.skill,sess_mode=EXCLUDED.sess_mode,status=EXCLUDED.status,requester=EXCLUDED.requester,review=EXCLUDED.review,"
                + "rating=EXCLUDED.rating,starts_at=EXCLUDED.starts_at")) {
            p.setInt(1, s.id); p.setString(2, s.teacher); p.setString(3, s.learner); p.setString(4, s.skill); p.setString(5, s.mode); p.setString(6, s.status); p.setString(7, s.requester);
            p.setString(8, nz(s.review)); p.setInt(9, s.rating); p.setTimestamp(10, Timestamp.valueOf(s.at)); p.executeUpdate();
        }
    }
    static void writeMsg(Msg m) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO messages(id,from_user,to_user,body,created) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
            p.setInt(1, m.id); p.setString(2, m.from); p.setString(3, m.to); p.setString(4, m.text); p.setLong(5, m.time); p.executeUpdate();
        }
    }
    static void writeReport(Report r) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO reports(id,by_user,target,reason,details,created) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
            p.setInt(1, r.id); p.setString(2, r.by); p.setString(3, r.target); p.setString(4, r.reason); p.setString(5, nz(r.details)); p.setLong(6, r.time); p.executeUpdate();
        }
    }
    /** Load everything from PostgreSQL (seeding demo data on an empty database). */
    static void initData() throws SQLException {
        S = fetch();
        if (S.users.isEmpty()) { S = new Store(); snap = new HashMap<>(); seed(); save(); } else snap = fingerprints(S);
        ver++;
    }
    /** Background poll: other computers' changes show up within ~3 seconds. */
    static void startPolling() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "db-poll"); t.setDaemon(true); return t; });
        poller.scheduleWithFixedDelay(() -> {
            try { if (dbRead == null) return; int v = ver; Store st = fetch(); Map<String, String> fp = fingerprints(st); SwingUtilities.invokeLater(() -> applyRemote(st, fp, v)); }
            catch (Exception e) { /* connection hiccup: try again next tick */ } }, 3, 3, TimeUnit.SECONDS);
    }
    static void applyRemote(Store st, Map<String, String> fp, int v) {
        if (busy > 0 || v != ver || fp.equals(snap)) return;
        Map<Integer, String> old = new HashMap<>(); for (Sess s : S.sessions) old.put(s.id, s.status); long cutoff = lastMsgSeen;
        S = st; snap = fp;
        if (me != null) {
            me = S.users.get(me.username); if (me == null) { rootCards.show(root, "login"); return; }
            for (Msg m : S.msgs) if (m.to.equals(me.username) && m.time > cutoff) { lastMsgSeen = Math.max(lastMsgSeen, m.time);
                if (!(page.equals("Messages") && m.from.equals(chatWith))) notify("New message", name(m.from) + ": " + m.text); }
            for (Sess s : S.sessions) if (involves(s, me)) { String o = old.get(s.id); boolean req = s.requester.equals(me.username);
                if (o == null && s.status.equals("PENDING") && !req) notify("New swap request", name(s.requester) + " wants a " + s.skill + " session");
                else if ("PENDING".equals(o) && req && s.status.equals("CONFIRMED")) notify("Swap accepted", name(other(s)) + " accepted your " + s.skill + " session");
                else if ("PENDING".equals(o) && req && s.status.equals("DECLINED")) notify("Swap declined", name(other(s)) + " declined your " + s.skill + " request"); }
            show(page);
        }
    }
    static String netText() {
        return db == null ? "Database: not connected" : "PostgreSQL: " + c("db.host", "localhost") + ":" + c("db.port", "5432") + "/" + c("db.name", "skillswap");
    }
    static void changeConnection() {
        if (!connectLoop("")) return;
        try { initData(); me = null; rootCards.show(root, "login"); toast("Connected to " + netText()); }
        catch (Exception e) { info("Could not load data: " + e.getMessage()); }
    }
    static Sess sess(int id) { return S.sessions.stream().filter(x -> x.id == id).findFirst().orElse(null); }

    static String hash(String user, String pw) {
        try { byte[] d = MessageDigest.getInstance("SHA-256").digest((user + ":" + pw).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString();
        } catch (Exception e) { return pw; }
    }

    // ---- skill categories ----
    static final Map<String, String> CAT = new LinkedHashMap<>();
    static {
        String[][] d = {{"Tech", "Java", "Python", "JavaScript", "C++", "Web Design"}, {"Music", "Guitar", "Piano", "Singing"},
                {"Arts", "Painting", "Drawing", "Photography"}, {"Academics", "Calculus", "Statistics", "Physics", "Chemistry"},
                {"Languages", "Russian", "Spanish", "Japanese"}, {"Cooking", "Baking", "Cooking"}};
        for (String[] r : d) for (int i = 1; i < r.length; i++) CAT.put(r[i].toLowerCase(), r[0]);
    }
    static String cat(String s) { return CAT.getOrDefault(s.toLowerCase().trim(), "Other"); }
    static final String[] CATS = {"All", "Tech", "Music", "Arts", "Academics", "Languages", "Cooking", "Other"};

    // ---- seed data ----
    static User addUser(String un, String real, String pw, String[] teach, String[] learn, boolean nearby, String bio) {
        User u = new User(); u.username = un; u.realName = real; u.useRealName = true; u.email = un + "@school.edu";
        u.passHash = hash(un, pw); u.teach.addAll(Arrays.asList(teach)); u.learn.addAll(Arrays.asList(learn));
        u.nearby = nearby; u.demo = true; u.bio = bio; S.users.put(un, u); return u;
    }
    static void rated(String t, String l, String skill, int r, String review) {
        Sess s = new Sess(); s.id = nextId(); s.teacher = t; s.learner = l; s.skill = skill; s.mode = "Online";
        s.status = "COMPLETED"; s.requester = l; s.rating = r; s.review = review; s.at = LocalDateTime.now().minusDays(5 + s.id).truncatedTo(ChronoUnit.SECONDS); S.sessions.add(s);
    }
    static void post(String a, String type, String skill, String desc) {
        Post p = new Post(); p.id = nextId(); p.author = a; p.type = type; p.skill = skill; p.category = cat(skill);
        p.desc = desc; p.time = System.currentTimeMillis() - (long) (Math.random() * 6e6); for (int i = 0; i < (int) (Math.random() * 7); i++) p.likes.add("seed" + i); S.posts.add(p);
    }
    static void seed() {
        addUser("ramos", "Dr. Ramos", "demo", new String[]{"Java", "Python", "Calculus"}, new String[]{"Guitar"}, true, "Senior Programmer Major");
        addUser("alamag", "Dr. Alamag", "demo", new String[]{"Guitar", "Piano"}, new String[]{"Python"}, true, "Conservatory Junior");
        addUser("henry", "Dr. Henry Flores", "demo", new String[]{"Statistics", "Russian"}, new String[]{"Painting"}, false, "Math & Linguistics");
        addUser("jeb", "Dr. Jeb Ramos", "demo", new String[]{"Painting", "Cooking"}, new String[]{"Java"}, false, "Fine Arts Sophomore");
        addUser("chinu", "Dr. Chinu Po", "demo", new String[]{"Calculus", "Physics"}, new String[]{"Russian"}, false, "Physics Senior");
        addUser("dino", "Dr. Dino Ran", "demo", new String[]{"Python", "Guitar"}, new String[]{"Calculus"}, true, "CS Junior");
        addUser("jamesa", "James A.", "demo", new String[]{"Spanish"}, new String[]{"Java"}, false, "");
        addUser("kylet", "Kyle T.", "demo", new String[]{"Drawing"}, new String[]{"Python"}, false, "");
        String[] rev = {"Super patient and clear.", "Explained recursion perfectly.", "Great session, very organized.", "Would swap again!"};
        rated("ramos", "jamesa", "Java", 5, rev[0]); rated("ramos", "kylet", "Python", 5, rev[1]);
        rated("ramos", "alamag", "Calculus", 4, rev[2]); rated("ramos", "henry", "Java", 5, rev[3]);
        rated("alamag", "dino", "Guitar", 5, rev[0]); rated("alamag", "jeb", "Piano", 4, rev[2]); rated("alamag", "kylet", "Guitar", 5, rev[3]);
        rated("henry", "chinu", "Statistics", 4, rev[2]); rated("henry", "jeb", "Russian", 4, rev[0]);
        rated("jeb", "ramos", "Painting", 5, rev[1]);
        rated("chinu", "dino", "Calculus", 4, rev[0]); rated("chinu", "ramos", "Physics", 5, rev[3]); rated("chinu", "henry", "Physics", 4, rev[2]);
        rated("dino", "ramos", "Guitar", 5, rev[1]); rated("dino", "jeb", "Python", 5, rev[0]); rated("dino", "alamag", "Python", 5, rev[3]);
        post("ramos", "OFFER", "Java", "Senior programmer major. Happy to teach OOP, data structures and debugging. Looking for a guitar teacher!");
        post("alamag", "OFFER", "Guitar", "Conservatory junior - beginner-friendly guitar lessons, any genre.");
        post("henry", "REQUEST", "Painting", "I can teach Russian or Statistics in return for basic watercolor lessons.");
        post("chinu", "OFFER", "Calculus", "Exam season is coming - calculus I & II help, problem-set walkthroughs.");
        post("jeb", "REQUEST", "Java", "Art student who wants to learn Java. I can teach painting and cooking!");
        post("dino", "OFFER", "Python", "Python from zero: scripts, pandas, small games.");
    }

    // ---- derived stats ----
    static List<Sess> reviews(User u) {
        return S.sessions.stream().filter(s -> s.status.equals("COMPLETED") && s.teacher.equals(u.username) && s.rating > 0).collect(Collectors.toList());
    }
    static double avg(User u) { return reviews(u).stream().mapToInt(s -> s.rating).average().orElse(0); }
    static boolean verified(User u) { return reviews(u).size() >= 3 && avg(u) >= 4.5; }
    static long taught(User u) { return S.sessions.stream().filter(s -> s.status.equals("COMPLETED") && s.teacher.equals(u.username)).count(); }
    static long learnedN(User u) { return S.sessions.stream().filter(s -> s.status.equals("COMPLETED") && s.learner.equals(u.username)).count(); }
    static String name(User u) { return u.useRealName && !u.realName.isEmpty() ? u.realName : u.username; }
    static String name(String un) { User u = S.users.get(un); return u == null ? un : name(u); }
    static String stars(double a) { int n = (int) Math.round(a); return "\u2605".repeat(n) + "\u2606".repeat(5 - n); }
    static List<String> inter(List<String> a, List<String> b) {
        return a.stream().filter(x -> b.stream().anyMatch(y -> y.equalsIgnoreCase(x))).collect(Collectors.toList());
    }
    static List<Match> matches() {
        List<Match> r = new ArrayList<>();
        for (User u : S.users.values()) {
            if (u == me) continue;
            Match m = new Match(); m.u = u; m.theyTeach = inter(u.teach, me.learn); m.iTeach = inter(me.teach, u.learn);
            if (!m.theyTeach.isEmpty()) r.add(m);
        }
        r.sort((x, y) -> y.iTeach.size() - x.iTeach.size());
        return r;
    }
    static List<String[]> badges(User u) {
        List<String[]> b = new ArrayList<>(); long t = taught(u), l = learnedN(u);
        b.add(new String[]{"Peer Tutor Level I", "Teach 1 completed session", t >= 1 ? "Earned" : "Locked", t + "/1 sessions"});
        b.add(new String[]{"Peer Tutor Level II", "Teach 5 completed sessions", t >= 5 ? "Earned" : t >= 1 ? "In progress" : "Locked", t + "/5 sessions"});
        b.add(new String[]{"Top Rated Tutor", "3+ reviews with a 4.5+ average", verified(u) ? "Earned" : reviews(u).size() > 0 ? "In progress" : "Locked",
                reviews(u).size() + "/3 reviews, avg " + String.format("%.1f", avg(u))});
        b.add(new String[]{"Curious Learner", "Learn from 3 different sessions", l >= 3 ? "Earned" : l >= 1 ? "In progress" : "Locked", l + "/3 sessions"});
        b.add(new String[]{"Skill Collector", "List 3 or more skills you teach", u.teach.size() >= 3 ? "Earned" : u.teach.size() > 0 ? "In progress" : "Locked", u.teach.size() + "/3 skills"});
        return b;
    }

    // ====================== THEME (dark / light) ======================
    static final Color PINK = new Color(0xE91E63), ONPINK = Color.WHITE;
    static Color BG, PANEL, PANEL2, TXT, MUTED, GREEN, GOLD, BLUE;
    static boolean dark = true;
    static void applyTheme(boolean d) {
        dark = d;
        if (d) { BG = new Color(0x0B1026); PANEL = new Color(0x141B3B); PANEL2 = new Color(0x1E2755); TXT = Color.WHITE; MUTED = new Color(0x8D96BD);
            GREEN = new Color(0x3DDC97); GOLD = new Color(0xFFC107); BLUE = new Color(0x6C8CFF); }
        else { BG = new Color(0xF1F3FA); PANEL = Color.WHITE; PANEL2 = new Color(0xE3E8F5); TXT = new Color(0x1B2140); MUTED = new Color(0x5C668C);
            GREEN = new Color(0x12A06B); GOLD = new Color(0xD08F00); BLUE = new Color(0x3858D6); }
        UIManager.put("OptionPane.background", PANEL); UIManager.put("Panel.background", PANEL); UIManager.put("OptionPane.messageForeground", TXT);
        UIManager.put("Label.foreground", TXT); UIManager.put("ComboBox.selectionBackground", PINK);
    }
    static void switchTheme(boolean d) {
        applyTheme(d); cfg.setProperty("theme", d ? "dark" : "light"); saveCfg();
        root.removeAll(); root.add(loginPanel(), "login"); root.add(appPanel(), "app");
        if (me != null) { rootCards.show(root, "app"); show(page); } else rootCards.show(root, "login");
        root.revalidate(); root.repaint();
    }
    static Color leftBg() { return dark ? new Color(0x090E24) : PANEL; }
    static Color divider() { return dark ? new Color(0x7F89B5) : new Color(0xC3CAE0); }

    // ====================== UI HELPERS ======================
    static Font f(int st, int sz) { return new Font("SansSerif", st, sz); }
    static JLabel lbl(String t, int st, int sz, Color c) { JLabel l = new JLabel(t); l.setFont(f(st, sz)); l.setForeground(c); return l; }
    static JPanel vbox() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setOpaque(false); return p; }
    static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4)); p.setOpaque(false); return p; }
    static void put(JPanel box, JComponent c) { c.setAlignmentX(0f); box.add(c); }
    static void gap(JPanel box, int h) { box.add(Box.createVerticalStrut(h)); }
    static void aa(Graphics2D g) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); }

    /** Rounded button: fills getBackground() with rounded corners, so every colour follows the theme. */
    static class RBtn extends JButton {
        int arc;
        RBtn(String t, int arc) { super(t); this.arc = arc; setContentAreaFilled(false); setOpaque(false); setBorderPainted(false); setFocusPainted(false); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create(); aa(g); g.setColor(getBackground()); g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc); g.dispose(); super.paintComponent(g0);
        }
    }
    static JButton btn(String t, Runnable r) {
        RBtn b = new RBtn(t, 18); b.setFont(f(Font.BOLD, 11)); b.setForeground(ONPINK); b.setBackground(PINK);
        b.setBorder(new EmptyBorder(6, 14, 6, 14)); b.setCursor(new Cursor(Cursor.HAND_CURSOR)); b.addActionListener(e -> r.run()); return b;
    }
    static JButton ghost(String t, Runnable r) { JButton b = btn(t, r); b.setBackground(PANEL2); b.setForeground(TXT); return b; }
    static JButton chip(String t, boolean on, Runnable r) { JButton b = on ? btn(t, r) : ghost(t, r); if (!on) b.setForeground(MUTED); return b; }
    static JLabel tag(String t, Color c) {
        JLabel l = lbl(t, Font.BOLD, 10, c); l.setBorder(new CompoundBorder(new LineBorder(c, 1, true), new EmptyBorder(1, 6, 1, 6))); return l;
    }
    static JPanel card() {
        JPanel p = new JPanel(new BorderLayout(10, 4)) {
            public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setBackground(PANEL); p.setBorder(new CompoundBorder(new LineBorder(PANEL2, 1, true), new EmptyBorder(10, 12, 10, 12)));
        return p;
    }
    static JTextField field() {
        JTextField t = new JTextField(); t.setBackground(PANEL2); t.setForeground(TXT); t.setCaretColor(TXT);
        t.setBorder(new CompoundBorder(new LineBorder(PINK.darker(), 1), new EmptyBorder(6, 8, 6, 8))); return t;
    }
    static JPasswordField pass() {
        JPasswordField t = new JPasswordField(); t.setBackground(PANEL2); t.setForeground(TXT); t.setCaretColor(TXT);
        t.setBorder(new CompoundBorder(new LineBorder(PINK.darker(), 1), new EmptyBorder(6, 8, 6, 8))); return t;
    }
    static JCheckBox check(String t, boolean v) { JCheckBox c = new JCheckBox(t, v); c.setOpaque(false); c.setForeground(TXT); c.setFont(f(Font.PLAIN, 12)); return c; }
    static <T> JComboBox<T> combo(T[] items) { JComboBox<T> c = new JComboBox<>(items); c.setBackground(PANEL2); c.setForeground(TXT); return c; }
    static JComponent avatar(String n, int sz) {
        String[] w = n.trim().split("\\s+"); String s = w[w.length - 1].isEmpty() ? "?" : w[w.length - 1].substring(0, 1).toUpperCase();
        return new JComponent() {
            { Dimension d = new Dimension(sz, sz); setPreferredSize(d); setMinimumSize(d); setMaximumSize(d); }
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0; aa(g);
                g.setColor(PANEL2); g.fillOval(0, 0, sz, sz); g.setColor(PINK); g.drawOval(0, 0, sz - 1, sz - 1);
                g.setColor(TXT); g.setFont(f(Font.BOLD, sz / 2)); FontMetrics m = g.getFontMetrics();
                g.drawString(s, (sz - m.stringWidth(s)) / 2, (sz + m.getAscent() - m.getDescent()) / 2);
            }
        };
    }
    static JPanel avatarWrap(String n, int sz) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); p.setOpaque(false); p.add(avatar(n, sz)); return p; }
    static JPanel form(String[] labels, JComponent[] comps) {
        JPanel p = new JPanel(new GridBagLayout()); p.setBackground(PANEL); GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            c.gridy = i; c.gridx = 0; c.weightx = 0; if (!labels[i].isEmpty()) p.add(lbl(labels[i], Font.PLAIN, 12, MUTED), c);
            c.gridx = 1; c.weightx = 1; p.add(comps[i], c);
        }
        return p;
    }
    static boolean ask(String title, JComponent body) {
        busy++; try { return JOptionPane.showConfirmDialog(frame, body, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION; } finally { busy--; }
    }
    static void info(String m) { busy++; try { JOptionPane.showMessageDialog(frame, m, "SkillSwap Campus", JOptionPane.INFORMATION_MESSAGE); } finally { busy--; } }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000; return m < 1 ? "just now" : m < 60 ? m + "m ago" : m < 1440 ? m / 60 + "h ago" : m / 1440 + "d ago";
    }
    static String tshort(long t) { long m = (System.currentTimeMillis() - t) / 60000; return m < 1 ? "now" : m < 60 ? m + "m" : m < 1440 ? m / 60 + "h" : m / 1440 + "d"; }
    static class Pg { JPanel root = new JPanel(new BorderLayout()), body = vbox(); }
    static Pg pg(String title, String sub, JComponent action) {
        Pg p = new Pg(); p.root.setBackground(BG);
        JPanel h = new JPanel(new BorderLayout()); h.setOpaque(false); h.setBorder(new EmptyBorder(14, 16, 6, 16));
        JPanel t = vbox(); put(t, lbl(title, Font.BOLD, 18, TXT)); put(t, lbl(sub, Font.PLAIN, 12, MUTED)); h.add(t, BorderLayout.CENTER);
        if (action != null) h.add(action, BorderLayout.EAST);
        JPanel wrap = new JPanel(new BorderLayout()); wrap.setBackground(BG); wrap.setBorder(new EmptyBorder(4, 16, 16, 16)); wrap.add(p.body, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap); sp.setBorder(null); sp.getViewport().setBackground(BG); sp.getVerticalScrollBar().setUnitIncrement(18);
        p.root.add(h, BorderLayout.NORTH); p.root.add(sp, BorderLayout.CENTER); return p;
    }

    // ====================== APP STATE / SHELL ======================
    static JFrame frame; static JPanel root, content, sidebar, rightPanel; static JScrollPane rightScroll; static CardLayout rootCards = new CardLayout(); static JLabel statusBar;
    static String page = "Feed", feedFilter = "All", searchQ = "", searchCat = "All", bookTab = "Upcoming", chatWith = null, board = "Most Active", chatQ = "";
    static TrayIcon tray; static String feedQ = "", certFilter = "All", draft = "", typingFrom; static LocalDate dayFilter; static final LinkedList<String> recent = new LinkedList<>();
    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("EEE, MMM d \u2022 h:mm a");

    public static void main(String[] a) {
        loadCfg(); applyTheme(!"light".equals(c("theme", "dark")));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { e.printStackTrace(); if (frame != null && frame.isShowing()) SwingUtilities.invokeLater(() -> toast("Error: " + e.getMessage())); });
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("SkillSwap Campus - Peer Learning"); frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { closeDb(); } });
            frame.setSize(1280, 780); frame.setMinimumSize(new Dimension(1050, 650)); frame.setLocationRelativeTo(null);
            if (!connectLoop("")) System.exit(0);
            try { initData(); } catch (Exception ex) { info("Could not load data from PostgreSQL:\n" + ex.getMessage()); System.exit(1); }
            root = new JPanel(rootCards); root.add(loginPanel(), "login"); root.add(appPanel(), "app");
            frame.setContentPane(root); frame.setVisible(true); rootCards.show(root, "login");
            startPolling(); new Timer(30000, e -> checkReminders()).start();
        });
    }

    static void notify(String title, String msg) {
        try {
            if (SystemTray.isSupported()) {
                if (tray == null) { BufferedImage im = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB); Graphics2D g = im.createGraphics();
                    g.setColor(PINK); g.fillOval(0, 0, 15, 15); g.dispose(); tray = new TrayIcon(im, "SkillSwap Campus"); SystemTray.getSystemTray().add(tray); }
                tray.displayMessage(title, msg, TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) { /* tray unavailable */ }
        toast(title + " - " + msg);
    }
    static void toast(String t) {
        if (frame == null || !frame.isShowing()) return;
        JWindow w = new JWindow(frame); JPanel p = new JPanel(new BorderLayout()); p.setBackground(PANEL2);
        p.setBorder(new CompoundBorder(new LineBorder(PINK, 1), new EmptyBorder(10, 14, 10, 14))); p.add(lbl("<html><body style='width:240px'>" + esc(t) + "</body></html>", Font.PLAIN, 12, TXT));
        w.setContentPane(p); w.pack(); Point o = frame.getLocationOnScreen();
        w.setLocation(o.x + frame.getWidth() - w.getWidth() - 20, o.y + frame.getHeight() - w.getHeight() - 50); w.setVisible(true);
        Timer x = new Timer(4000, e -> w.dispose()); x.setRepeats(false); x.start();
    }
    static void checkReminders() {
        if (me == null) return; LocalDateTime now = LocalDateTime.now();
        for (Sess s : S.sessions) if (s.status.equals("CONFIRMED") && !remindedIds.contains(s.id) && involves(s, me) && s.at.isAfter(now.minusMinutes(5)) && s.at.isBefore(now.plusMinutes(60))) {
            remindedIds.add(s.id); notify("Upcoming session", s.skill + " with " + name(other(s)) + " at " + s.at.format(DateTimeFormatter.ofPattern("h:mm a")));
        }
    }
    static boolean involves(Sess s, User u) { return s.teacher.equals(u.username) || s.learner.equals(u.username); }
    static String other(Sess s) { return s.teacher.equals(me.username) ? s.learner : s.teacher; }

    // ====================== LOGIN ======================
    static JComponent iconTile(int type) {
        return new JComponent() {
            { setPreferredSize(new Dimension(74, 74)); }
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0; aa(g);
                g.setColor(new Color(0x2A2A2A)); g.fillRoundRect(0, 0, 73, 73, 8, 8); g.setColor(Color.WHITE); g.setStroke(new BasicStroke(2f));
                if (type == 2) { g.drawOval(20, 16, 28, 28); g.drawLine(44, 42, 56, 56); }
                else { g.drawPolygon(new int[]{36, 62, 36, 10}, new int[]{18, 30, 42, 30}, 4); g.drawArc(22, 36, 28, 18, 180, 180); g.drawLine(62, 30, 62, 44); }
            }
        };
    }
    static JComponent loginPanel() {
        JPanel bg = new JPanel(new GridBagLayout()); bg.setBackground(BG);
        JPanel c = vbox(); c.setBackground(PANEL); c.setOpaque(true); c.setBorder(new CompoundBorder(new LineBorder(PANEL2, 1, true), new EmptyBorder(18, 28, 14, 28)));
        JLabel title = lbl("LOGIN TO SKILLSWAP", Font.BOLD, 13, TXT); JPanel tp = new JPanel(new FlowLayout(FlowLayout.CENTER)); tp.setOpaque(false); tp.add(title); put(c, tp); gap(c, 6);
        JPanel icons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0)); icons.setOpaque(false); for (int i = 0; i < 3; i++) icons.add(iconTile(i)); put(c, icons); gap(c, 14);
        JTextField u = field(); JPasswordField p = pass();
        put(c, lbl("Username or school email", Font.PLAIN, 11, MUTED)); put(c, u); gap(c, 6); put(c, lbl("Password", Font.PLAIN, 11, MUTED)); put(c, p); gap(c, 4);
        JButton forgot = ghost("<html><u>Forgot Password?</u></html>", SkillSwapCampus::forgot); forgot.setBackground(PANEL); forgot.setForeground(TXT); forgot.setFont(f(Font.PLAIN, 10));
        JPanel fr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); fr.setOpaque(false); fr.add(forgot); put(c, fr); gap(c, 6);
        Runnable go = () -> {
            String key = u.getText().trim(); String pw = new String(p.getPassword());
            User f = S.users.values().stream().filter(x -> x.username.equalsIgnoreCase(key) || x.email.equalsIgnoreCase(key)).findFirst().orElse(null);
            if (f == null || !f.passHash.equals(hash(f.username, pw))) { JOptionPane.showMessageDialog(frame, "Incorrect username/email or password.", "Sign in failed", JOptionPane.ERROR_MESSAGE); return; }
            u.setText(""); p.setText(""); enter(f);
        };
        p.addActionListener(e -> go.run()); u.addActionListener(e -> go.run());
        JButton si = btn("Sign in", go); si.setBorder(new EmptyBorder(9, 12, 9, 12)); si.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); put(c, si); gap(c, 6);
        JButton reg = ghost("Create account", SkillSwapCampus::register); reg.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); put(c, reg); gap(c, 10);
        JLabel net = lbl(netText(), Font.PLAIN, 10, db == null ? GOLD : GREEN); JPanel nl = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0)); nl.setOpaque(false); nl.add(net); put(c, nl); gap(c, 4);
        JPanel nr = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0)); nr.setOpaque(false);
        nr.add(ghost("Database settings", () -> { changeConnection(); net.setText(netText()); }));
        nr.add(ghost(dark ? "Light mode" : "Dark mode", () -> switchTheme(!dark)));
        put(c, nr); gap(c, 8);
        JPanel ft = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0)); ft.setOpaque(false); ft.add(lbl("Contact us: SkillSwap support | Privacy | T&C", Font.PLAIN, 9, TXT)); put(c, ft);
        c.setPreferredSize(new Dimension(400, 560)); bg.add(c); return bg;
    }
    static void enter(User u) {
        me = u; page = "Feed"; lastMsgSeen = System.currentTimeMillis(); draft = ""; chatWith = null; rootCards.show(root, "app"); show("Feed"); checkReminders();
        notify("Welcome, " + name(u), "You have " + u.credits + " swap credits.");
    }
    static void register() {
        JTextField em = field(), un = field(), rn = field(); JPasswordField p1 = pass(), p2 = pass();
        JCheckBox real = check("Show my real name publicly (otherwise username)", false), pub = check("Make my ratings public", true);
        JPanel f = form(new String[]{"School email", "Username", "Real name", "Password", "Confirm", "", ""}, new JComponent[]{em, un, rn, p1, p2, real, pub});
        f.setPreferredSize(new Dimension(420, 270));
        if (!ask("Create account", f)) return;
        String email = em.getText().trim().toLowerCase(), user = un.getText().trim(), pw = new String(p1.getPassword());
        String err = !email.matches("^[\\w.+-]+@[\\w.-]*edu[\\w.-]*$") ? "Please use your school email (a domain containing .edu)."
                : user.length() < 3 ? "Username must be at least 3 characters." : S.users.keySet().stream().anyMatch(x -> x.equalsIgnoreCase(user)) || S.users.values().stream().anyMatch(x -> x.email.equals(email)) ? "That username or email is already registered."
                : pw.length() < 6 ? "Password must be at least 6 characters." : !pw.equals(new String(p2.getPassword())) ? "Passwords do not match." : null;
        if (err != null) { JOptionPane.showMessageDialog(frame, err, "Cannot create account", JOptionPane.WARNING_MESSAGE); return; }
        String code = String.format("%06d", new Random().nextInt(1000000)); notify("SkillSwap verification", "Your code is " + code);
        String in = JOptionPane.showInputDialog(frame, "Enter the 6-digit code sent to " + email + "\n(Demo: no mail server is configured - your code is " + code + ")");
        if (in == null || !in.trim().equals(code)) { info("Verification failed. Account not created."); return; }
        User u = new User(); u.username = user; u.email = email; u.realName = rn.getText().trim(); u.useRealName = real.isSelected() && !u.realName.isEmpty();
        u.publicRatings = pub.isSelected(); u.passHash = hash(user, pw); S.users.put(user, u); save(); info("Account created! Add the skills you teach and want to learn in My Profile."); enter(u); show("Profile");
    }
    static void forgot() {
        JTextField em = field(); JPasswordField np = pass();
        if (!ask("Reset password", form(new String[]{"School email", "New password"}, new JComponent[]{em, np}))) return;
        User u = S.users.values().stream().filter(x -> x.email.equalsIgnoreCase(em.getText().trim())).findFirst().orElse(null);
        if (u == null || np.getPassword().length < 6) { info("Email not found, or password shorter than 6 characters."); return; }
        String code = String.format("%06d", new Random().nextInt(1000000));
        String in = JOptionPane.showInputDialog(frame, "Enter the code sent to your school email\n(Demo code: " + code + ")");
        if (in != null && in.trim().equals(code)) { u.passHash = hash(u.username, new String(np.getPassword())); save(); info("Password updated."); } else info("Wrong code.");
    }

    // ====================== MAIN APP SHELL ======================
    static final String[][] NAV = {{"Feed", "Skill Feed"}, {"Search", "Search Topics"}, {"Certs", "Verified Certificates"}, {"Bookings", "Bookings & Sessions"},
            {"Nearby", "Nearby Now"}, {"Messages", "Messages"}, {"Profile", "My Profile & Rating"}, {"Settings", "Settings"}};
    static JComponent appPanel() {
        JPanel p = new JPanel(new BorderLayout()); p.setBackground(BG);
        sidebar = new JPanel(); sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS)); sidebar.setBackground(PANEL); sidebar.setPreferredSize(new Dimension(190, 0));
        content = new JPanel(new BorderLayout()); content.setBackground(BG);
        rightPanel = new JPanel(); rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS)); rightPanel.setBackground(PANEL);
        rightScroll = new JScrollPane(rightPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); rightScroll.setBorder(null);
        rightScroll.setPreferredSize(new Dimension(270, 0)); rightScroll.getViewport().setBackground(PANEL);
        statusBar = lbl(" ", Font.PLAIN, 11, MUTED); statusBar.setBorder(new EmptyBorder(3, 10, 3, 10)); JPanel sb = new JPanel(new BorderLayout()); sb.setBackground(PANEL); sb.add(statusBar);
        p.add(sidebar, BorderLayout.WEST); p.add(content, BorderLayout.CENTER); p.add(rightScroll, BorderLayout.EAST); p.add(sb, BorderLayout.SOUTH); return p;
    }
    static void show(String pgName) {
        page = pgName; content.removeAll();
        JComponent c;
        switch (pgName) {
            case "Search": c = searchPage(); break; case "Certs": c = certsPage(); break; case "Bookings": c = bookingsPage(); break;
            case "Nearby": c = nearbyPage(); break; case "Messages": c = messagesPage(); break; case "Profile": c = profilePage(); break;
            case "Settings": c = settingsPage(); break; default: c = feedPage();
        }
        content.add(c); content.revalidate(); content.repaint(); buildSidebar();
        boolean side = !(pgName.equals("Messages") || pgName.equals("Settings"));   // chat & settings use the full width
        rightScroll.setVisible(side); if (side) buildRight(); rightScroll.getParent().revalidate();
        statusBar.setText(netText() + "   |   Signed in as " + name(me) + "   |   Credits: " + me.credits + "/" + MAX_CREDITS + "   |   Last online sync: " + S.lastSync);
    }
    static void buildSidebar() {
        sidebar.removeAll(); sidebar.setBorder(new EmptyBorder(14, 10, 12, 10));
        JPanel logo = new JPanel(new BorderLayout(8, 0)); logo.setOpaque(false); JPanel sq = new JPanel(); sq.setBackground(new Color(0xA03CFF)); sq.setPreferredSize(new Dimension(16, 16)); JPanel sqw = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3)); sqw.setOpaque(false); sqw.add(sq); JPanel tx = vbox(); put(tx, lbl("Skillswap", Font.BOLD, 14, TXT)); put(tx, lbl("Peer Learning", Font.PLAIN, 10, PINK)); logo.add(sqw, BorderLayout.WEST); logo.add(tx); logo.setBorder(new EmptyBorder(0, 4, 16, 0)); logo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50)); put(sidebar, logo);
        for (String[] n : NAV) {
            boolean on = n[0].equals(page);
            RBtn b = new RBtn(n[1], 26); b.setHorizontalAlignment(SwingConstants.LEFT); b.setFont(f(Font.BOLD, 12)); b.setForeground(on ? ONPINK : MUTED);
            b.setBackground(on ? PINK : PANEL); b.setBorder(new EmptyBorder(8, 12, 8, 12));
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36)); b.setCursor(new Cursor(Cursor.HAND_CURSOR));
            if (n[0].equals("Certs")) {
                long earned = badges(me).stream().filter(x -> x[2].equals("Earned")).count();
                if (earned > 0) {
                    JLabel bd = new JLabel("" + earned, SwingConstants.CENTER) { protected void paintComponent(Graphics g0) { Graphics2D g = (Graphics2D) g0.create(); aa(g); g.setColor(getBackground()); g.fillOval(0, 0, getWidth(), getHeight()); g.dispose(); super.paintComponent(g0); } };
                    bd.setOpaque(false); bd.setBackground(new Color(0x7B3FB0)); bd.setForeground(Color.WHITE); bd.setFont(f(Font.BOLD, 10)); bd.setPreferredSize(new Dimension(20, 20));
                    JPanel w = new JPanel(new GridBagLayout()); w.setOpaque(false); w.add(bd); b.setLayout(new BorderLayout()); b.add(w, BorderLayout.EAST);
                }
            }
            String t = n[0]; b.addActionListener(e -> show(t)); put(sidebar, b); gap(sidebar, 4);
        }
        sidebar.add(Box.createVerticalGlue());
        JButton out = ghost("Log Out", () -> { save(); me = null; rootCards.show(root, "login"); }); out.setBackground(PANEL); out.setForeground(MUTED); put(sidebar, out);
        sidebar.revalidate(); sidebar.repaint();
    }
    static void buildRight() {
        rightPanel.removeAll(); rightPanel.setBorder(new EmptyBorder(14, 12, 12, 12));
        if (page.equals("Profile")) { buildRightProfile(); rightPanel.add(Box.createVerticalGlue()); rightPanel.revalidate(); rightPanel.repaint(); return; }
        put(rightPanel, lbl("TOP TUTORS", Font.BOLD, 12, MUTED)); gap(rightPanel, 4);
        JPanel chips = row();
        for (String bd : new String[]{"Most Active", "Most Learned"}) chips.add(chip(bd, bd.equals(board), () -> { board = bd; buildRight(); }));
        put(rightPanel, chips);
        List<User> us = new ArrayList<>(S.users.values());
        us.sort((x, y) -> board.equals("Most Active") ? Long.compare(taught(y), taught(x)) : Long.compare(learnedN(y), learnedN(x)));
        int rank = 1;
        for (User u : us.subList(0, Math.min(5, us.size()))) {
            JPanel r = card(); r.setBorder(new EmptyBorder(6, 8, 6, 8)); JPanel inf = vbox();
            JPanel nm = row(); nm.add(lbl(rank++ + ". " + name(u), Font.BOLD, 12, TXT)); if (verified(u)) nm.add(tag("\u2713", GREEN)); put(inf, nm);
            put(inf, lbl((board.equals("Most Active") ? taught(u) + " sessions taught" : learnedN(u) + " skills learned") + (u.publicRatings && !reviews(u).isEmpty() ? "  " + String.format("%.1f", avg(u)) + "\u2605" : ""), Font.PLAIN, 11, MUTED));
            r.add(inf, BorderLayout.CENTER);
            if (u != me && !u.teach.isEmpty()) r.add(btn("Book", () -> book(u.username, me.username, u.teach.get(0))), BorderLayout.EAST);
            put(rightPanel, r); gap(rightPanel, 5);
        }
        gap(rightPanel, 10); put(rightPanel, lbl("CAMPUS BADGES", Font.BOLD, 12, MUTED)); gap(rightPanel, 4);
        JPanel cr = card(); cr.setBorder(new EmptyBorder(6, 8, 6, 8)); JPanel ci = vbox();
        put(ci, lbl("Swap credits: " + me.credits + " / " + MAX_CREDITS, Font.BOLD, 12, TXT));
        JProgressBar pb = new JProgressBar(0, MAX_CREDITS); pb.setValue(me.credits); pb.setForeground(PINK); pb.setBackground(PANEL2); pb.setBorderPainted(false); put(ci, pb);
        put(ci, lbl("Learning costs 1, teaching earns 1.", Font.PLAIN, 10, MUTED)); cr.add(ci); put(rightPanel, cr); gap(rightPanel, 5);
        for (String[] b : badges(me)) if (b[2].equals("Earned")) { JPanel br = card(); br.setBorder(new EmptyBorder(6, 8, 6, 8)); br.add(lbl("\u2611 " + b[0], Font.BOLD, 11, GOLD)); put(rightPanel, br); gap(rightPanel, 4); }
        rightPanel.add(Box.createVerticalGlue()); rightPanel.revalidate(); rightPanel.repaint();
    }
    static void buildRightProfile() {
        put(rightPanel, lbl("MY RATING", Font.BOLD, 12, MUTED)); gap(rightPanel, 6); List<Sess> rs = reviews(me);
        JPanel big = row(); big.add(lbl(rs.isEmpty() ? "-" : String.format("%.1f", avg(me)), Font.BOLD, 28, TXT)); big.add(lbl(stars(avg(me)) + "  (" + rs.size() + ")", Font.PLAIN, 12, GOLD)); put(rightPanel, big);
        if (!me.publicRatings) put(rightPanel, lbl("Private - only you can see this", Font.PLAIN, 10, MUTED));
        for (int st = 5; st >= 1; st--) { final int s2 = st; long n = rs.stream().filter(x -> x.rating == s2).count(); JPanel r = new JPanel(new BorderLayout(8, 0)); r.setOpaque(false);
            r.add(lbl(st + " \u2605", Font.PLAIN, 11, GOLD), BorderLayout.WEST); JProgressBar pb = new JProgressBar(0, Math.max(1, rs.size())); pb.setValue((int) n); pb.setForeground(PINK); pb.setBackground(PANEL2); pb.setBorderPainted(false);
            r.add(pb); r.add(lbl("" + n, Font.PLAIN, 11, MUTED), BorderLayout.EAST); put(rightPanel, r); gap(rightPanel, 3); }
        gap(rightPanel, 12); put(rightPanel, lbl("MY BADGES", Font.BOLD, 12, MUTED)); gap(rightPanel, 4);
        for (String[] b : badges(me)) { JPanel br = card(); br.setBorder(new EmptyBorder(6, 8, 6, 8)); boolean ok = b[2].equals("Earned"); br.add(lbl((ok ? "\u2605 " : "\u2606 ") + b[0], Font.BOLD, 11, ok ? GOLD : MUTED)); put(rightPanel, br); gap(rightPanel, 4); }
        gap(rightPanel, 8); JButton sh = btn("Share Profile", SkillSwapCampus::shareProfile); sh.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38)); put(rightPanel, sh);
    }
    static void shareProfile() {
        List<Sess> rs = reviews(me);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection("SkillSwap Campus - " + name(me) + " | Teaches: " + String.join(", ", me.teach) + " | Wants: " + String.join(", ", me.learn)
                + (me.publicRatings && !rs.isEmpty() ? " | Rating " + String.format("%.1f", avg(me)) : "")), null);
        toast("Profile summary copied to clipboard");
    }

    // ====================== SETTINGS ======================
    static JComponent settingsPage() {
        Pg p = pg("Settings", "Appearance and database connection", null);
        JPanel ap = card(); JPanel ai = vbox();
        put(ai, lbl("Appearance", Font.BOLD, 14, TXT)); put(ai, lbl("Choose how SkillSwap Campus looks on this computer. Your choice is remembered.", Font.PLAIN, 12, MUTED)); gap(ai, 6);
        JPanel r = row(); r.add(chip("Dark mode", dark, () -> { if (!dark) switchTheme(true); })); r.add(chip("Light mode", !dark, () -> { if (dark) switchTheme(false); })); put(ai, r);
        ap.add(ai); put(p.body, ap); gap(p.body, 10);
        JPanel dp = card(); JPanel di = vbox();
        put(di, lbl("Database (PostgreSQL)", Font.BOLD, 14, TXT)); put(di, lbl(netText() + "   as   " + c("db.user", "postgres"), Font.PLAIN, 12, MUTED));
        put(di, lbl(db == null ? "Status: not connected" : "Status: connected", Font.BOLD, 12, db == null ? GOLD : GREEN)); gap(di, 6);
        JPanel dr = row();
        dr.add(ghost("Test connection", () -> { try { info(db != null && db.isValid(3) ? "Connection OK." : "Connection is not valid."); } catch (SQLException e) { info("Connection failed: " + e.getMessage()); } }));
        dr.add(ghost("Change connection", SkillSwapCampus::changeConnection)); put(di, dr);
        dp.add(di); put(p.body, dp); gap(p.body, 10);
        JPanel ac = card(); JPanel ci = vbox();
        put(ci, lbl("Account", Font.BOLD, 14, TXT)); put(ci, lbl("Signed in as " + name(me) + " (" + me.email + ")", Font.PLAIN, 12, MUTED)); ac.add(ci); put(p.body, ac);
        return p.root;
    }

    // ====================== FEED ======================
    static Color catColor(String c) {
        switch (c) { case "Tech": return BLUE; case "Music": return PINK; case "Arts": return new Color(0xFF9800); case "Academics": return GREEN;
            case "Languages": return new Color(0xAB47BC); case "Cooking": return GOLD; default: return new Color(0x607D8B); }
    }
    static JComponent swatch(Color c, int sz) {
        return new JComponent() {
            { Dimension d = new Dimension(sz, sz); setPreferredSize(d); setMinimumSize(d); setMaximumSize(d); }
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0; aa(g);
                g.setColor(c.darker().darker()); g.fillRoundRect(0, 0, sz, sz, 10, 10); g.setColor(c); g.drawRoundRect(0, 0, sz - 1, sz - 1, 10, 10); g.fillRoundRect(sz / 4, sz / 4, sz / 2, sz / 2, 6, 6);
            }
        };
    }
    static JPanel swatchWrap(Color c, int sz) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); p.setOpaque(false); p.add(swatch(c, sz)); return p; }
    static JTextField searchField(String hint) {
        JTextField t = new JTextField() { protected void paintComponent(Graphics g) { super.paintComponent(g);
            if (getText().isEmpty()) { g.setColor(MUTED); g.setFont(getFont()); g.drawString(hint, 12, (getHeight() + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2); } } };
        t.setBackground(PANEL); t.setForeground(TXT); t.setCaretColor(TXT); t.setFont(f(Font.PLAIN, 13));
        t.setBorder(new CompoundBorder(new LineBorder(PANEL2, 1, true), new EmptyBorder(9, 12, 9, 12))); return t;
    }
    static String snippet(String skill) {
        switch (skill.toLowerCase()) {
            case "java": return "for (Skill s : teach) {\n    if (want.contains(s)) swap(s);\n}";
            case "python": return "for s in teach:\n    if s in want:\n        swap(s)";
            case "javascript": return "teach.filter(s => want.has(s))\n     .forEach(swap);";
            default: return null;
        }
    }
    static JComponent feedPage() {
        Pg p = pg("Skill Feed", "Offers and requests from your campus", btn("+ New Post", SkillSwapCampus::newPost));
        JTextField q = searchField("Search topics (e.g., Java, Guitar)"); q.setText(feedQ); q.addActionListener(e -> { feedQ = q.getText().trim(); show("Feed"); }); put(p.body, q); gap(p.body, 8);
        JPanel chips = row(); for (String x : new String[]{"All", "Offers", "Requests", "Matches"}) chips.add(chip(x, x.equals(feedFilter), () -> { feedFilter = x; show("Feed"); })); put(p.body, chips); gap(p.body, 6);
        if (feedFilter.equals("Matches")) {
            List<Match> ms = matches();
            if (ms.isEmpty()) put(p.body, lbl("No matches yet - add skills you want to learn in My Profile & Rating.", Font.PLAIN, 12, MUTED));
            for (Match m : ms) { put(p.body, matchCard(m)); gap(p.body, 8); }
            return p.root;
        }
        List<Post> ps = new ArrayList<>(S.posts); Collections.reverse(ps); int n = 0;
        for (Post po : ps) {
            if (feedFilter.equals("Offers") && !po.type.equals("OFFER")) continue; if (feedFilter.equals("Requests") && !po.type.equals("REQUEST")) continue;
            if (!feedQ.isEmpty() && !(po.skill + " " + po.desc + " " + po.category + " " + name(po.author)).toLowerCase().contains(feedQ.toLowerCase())) continue;
            put(p.body, postCard(po)); gap(p.body, 8); n++;
        }
        if (n == 0) put(p.body, lbl("No posts match. Try another search or create a post.", Font.PLAIN, 12, MUTED));
        return p.root;
    }
    static JComponent matchCard(Match m) {
        JPanel c = card(); c.add(avatarWrap(name(m.u), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
        h.add(lbl(name(m.u), Font.BOLD, 13, TXT)); h.add(m.iTeach.isEmpty() ? tag("CAN TEACH YOU", BLUE) : tag("PERFECT SWAP", GREEN)); if (verified(m.u)) h.add(tag("VERIFIED", GREEN)); put(inf, h);
        put(inf, lbl("Teaches you: " + String.join(", ", m.theyTeach) + (m.iTeach.isEmpty() ? "" : "   \u2022   Wants to learn from you: " + String.join(", ", m.iTeach)), Font.PLAIN, 12, MUTED));
        c.add(inf, BorderLayout.CENTER); JPanel b = row(); b.add(btn("REQUEST SWAP", () -> book(m.u.username, me.username, m.theyTeach.get(0)))); b.add(ghost("Message", () -> { chatWith = m.u.username; draft = ""; show("Messages"); }));
        c.add(b, BorderLayout.EAST); return c;
    }
    static JComponent postCard(Post po) {
        User a = S.users.get(po.author); boolean mine = po.author.equals(me.username); boolean offer = po.type.equals("OFFER");
        JPanel c = card(); c.add(avatarWrap(name(po.author), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
        h.add(lbl(name(po.author), Font.BOLD, 13, TXT)); if (a != null && verified(a)) h.add(tag("VERIFIED", GREEN)); h.add(tag(offer ? "OFFERING" : "REQUESTING", offer ? GREEN : GOLD)); put(inf, h);
        if (a != null && !a.bio.isEmpty()) put(inf, lbl(a.bio, Font.PLAIN, 11, MUTED));
        put(inf, lbl("#" + po.skill.replace(" ", "") + "  #" + po.category + (offer ? "  #teaching" : "  #learning"), Font.BOLD, 11, PINK));
        put(inf, lbl("<html><body style='width:400px'>" + esc(po.desc) + "</body></html>", Font.PLAIN, 12, MUTED));
        String sn = snippet(po.skill);
        if (sn != null) { JTextArea ta = new JTextArea(sn); ta.setEditable(false); ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); ta.setBackground(Color.BLACK); ta.setForeground(new Color(0x3DDC97)); ta.setBorder(new EmptyBorder(8, 10, 8, 10)); gap(inf, 4); put(inf, ta); }
        JPanel foot = row(); JButton like = ghost("\u2665 " + po.likes.size(), () -> { if (!po.likes.remove(me.username)) po.likes.add(me.username); save(); show("Feed"); });
        like.setForeground(po.likes.contains(me.username) ? PINK : MUTED); foot.add(like); foot.add(lbl(ago(po.time), Font.PLAIN, 11, MUTED)); put(inf, foot);
        c.add(inf, BorderLayout.CENTER);
        JPanel ea = vbox(); ea.setPreferredSize(new Dimension(170, 80));
        if (a != null && a.publicRatings && !reviews(a).isEmpty()) put(ea, lbl("\u2605 " + String.format("%.1f", avg(a)) + " rating", Font.BOLD, 11, GOLD)); gap(ea, 6);
        if (mine) put(ea, ghost("Delete", () -> { S.posts.remove(po); save(); show("Feed"); }));
        else {
            put(ea, btn(offer ? "BOOK SESSION" : "OFFER TO TEACH", () -> { if (offer) book(po.author, me.username, po.skill); else book(me.username, po.author, po.skill); })); gap(ea, 4);
            JPanel sm = row(); sm.add(ghost("Message", () -> { chatWith = po.author; draft = ""; show("Messages"); })); sm.add(ghost("View", () -> profileDialog(a))); sm.add(ghost("\u2691", () -> report(po.author))); put(ea, sm);
        }
        c.add(ea, BorderLayout.EAST); return c;
    }
    static void newPost() {
        JComboBox<String> type = combo(new String[]{"OFFER (I can teach)", "REQUEST (I want to learn)"});
        JComboBox<String> skill = combo(CAT.keySet().stream().map(s -> s.substring(0, 1).toUpperCase() + s.substring(1)).toArray(String[]::new)); skill.setEditable(true);
        JTextArea d = new JTextArea(4, 24); d.setLineWrap(true); d.setWrapStyleWord(true); d.setBackground(PANEL2); d.setForeground(TXT); d.setCaretColor(TXT);
        if (!ask("New post", form(new String[]{"Type", "Skill", "Details"}, new JComponent[]{type, skill, new JScrollPane(d)}))) return;
        String sk = String.valueOf(skill.getSelectedItem()).trim(); if (sk.isEmpty()) return;
        Post po = new Post(); po.id = nextId(); po.author = me.username; po.type = type.getSelectedIndex() == 0 ? "OFFER" : "REQUEST"; po.skill = sk; po.category = cat(sk);
        po.desc = d.getText().trim().isEmpty() ? "(no details)" : d.getText().trim(); po.time = System.currentTimeMillis(); S.posts.add(po);
        List<String> l = po.type.equals("OFFER") ? me.teach : me.learn; if (l.stream().noneMatch(x -> x.equalsIgnoreCase(sk))) l.add(sk);
        save(); feedFilter = "All"; show("Feed"); toast("Post published");
    }

    // ====================== SEARCH ======================
    static JComponent searchPage() {
        Pg p = pg("Search Topics", "Find a peer tutor by skill or category", null);
        JTextField q = searchField("Search topics (e.g., Java, Guitar)"); q.setText(searchQ);
        Runnable go = () -> { searchQ = q.getText().trim(); if (!searchQ.isEmpty()) { recent.remove(searchQ); recent.addFirst(searchQ); while (recent.size() > 5) recent.removeLast(); } show("Search"); };
        q.addActionListener(e -> go.run());
        JPanel sr = new JPanel(new BorderLayout(6, 0)); sr.setOpaque(false); sr.add(q); sr.add(btn("Search", go), BorderLayout.EAST); put(p.body, sr); gap(p.body, 6);
        if (!recent.isEmpty()) { put(p.body, lbl("Recent searches", Font.PLAIN, 11, MUTED)); JPanel rr = row(); for (String s : recent) rr.add(chip(s, false, () -> { searchQ = s; show("Search"); })); put(p.body, rr); }
        JPanel chips = row(); for (String c : CATS) chips.add(chip(c, c.equals(searchCat), () -> { searchCat = c; show("Search"); })); put(p.body, chips); gap(p.body, 8);
        if (!searchQ.isEmpty() || !searchCat.equals("All")) {
            put(p.body, lbl("Results", Font.BOLD, 13, MUTED)); gap(p.body, 4); int n = 0;
            for (User u : S.users.values()) { if (u == me) continue;
                for (String s : u.teach) if ((searchQ.isEmpty() || s.toLowerCase().contains(searchQ.toLowerCase())) && (searchCat.equals("All") || cat(s).equals(searchCat))) { put(p.body, tutorCard(u, s)); gap(p.body, 6); n++; } }
            if (n == 0) put(p.body, lbl("No tutors found. Try posting a request in the Skill Feed!", Font.PLAIN, 12, MUTED));
            gap(p.body, 10);
        }
        put(p.body, lbl("Trending Topics", Font.BOLD, 13, MUTED)); gap(p.body, 4);
        Map<String, Integer> cnt = new TreeMap<>(); for (User u : S.users.values()) if (u != me) for (String s : u.teach) cnt.merge(s, 1, Integer::sum);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(cnt.entrySet()); es.sort((x, y) -> y.getValue() - x.getValue());
        JPanel g = new JPanel(new GridLayout(0, 2, 10, 10)); g.setOpaque(false);
        for (Map.Entry<String, Integer> en : es) {
            Color cc = catColor(cat(en.getKey())); JPanel t = card(); t.add(swatchWrap(cc, 34), BorderLayout.WEST);
            JPanel mid = vbox(); put(mid, lbl(en.getKey(), Font.BOLD, 13, TXT)); put(mid, lbl(en.getValue() + " tutors", Font.PLAIN, 11, MUTED)); t.add(mid); t.add(tag(cat(en.getKey()).toUpperCase(), cc), BorderLayout.EAST);
            t.setCursor(new Cursor(Cursor.HAND_CURSOR)); t.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent ev) { searchQ = en.getKey(); show("Search"); } }); g.add(t);
        }
        put(p.body, g); return p.root;
    }
    static JComponent tutorCard(User u, String skill) {
        JPanel c = card(); c.add(avatarWrap(name(u), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
        h.add(lbl(name(u), Font.BOLD, 13, TXT)); if (verified(u)) h.add(tag("VERIFIED", GREEN)); h.add(tag(skill, PINK)); if (u.nearby) h.add(tag("NEARBY NOW", GOLD)); put(inf, h);
        put(inf, lbl(u.publicRatings ? (reviews(u).isEmpty() ? "No reviews yet" : stars(avg(u)) + " " + String.format("%.1f", avg(u)) + " (" + reviews(u).size() + ")") : "Ratings are private", Font.PLAIN, 11, GOLD));
        if (!u.bio.isEmpty()) put(inf, lbl(u.bio, Font.PLAIN, 11, MUTED)); c.add(inf);
        JPanel b = row(); b.add(btn("BOOK", () -> book(u.username, me.username, skill))); b.add(ghost("Message", () -> { chatWith = u.username; show("Messages"); })); b.add(ghost("View", () -> profileDialog(u))); c.add(b, BorderLayout.EAST); return c;
    }

    // ====================== CERTIFICATES ======================
    static JComponent certsPage() {
        List<String[]> bs = badges(me); long earned = bs.stream().filter(b -> b[2].equals("Earned")).count(), prog = bs.stream().filter(b -> b[2].equals("In progress")).count(), lock = bs.size() - earned - prog;
        Pg p = pg("Verified Certificates", "Credentials earned by teaching and reviews", btn("+ Request", () -> {
            if (verified(me)) info("You already hold the Top Rated Tutor verification badge. Great work!");
            else info("Verification is automatic once you have 3+ reviews with a 4.5+ average.\nYou currently have " + reviews(me).size() + " review(s), average " + String.format("%.1f", avg(me)) + ".\nComplete more sessions to qualify."); }));
        JPanel stats = new JPanel(new GridLayout(1, 3, 8, 0)); stats.setOpaque(false);
        Object[][] sv = {{"" + earned, "Earned", GREEN}, {"" + prog, "In progress", GOLD}, {"" + lock, "Locked", MUTED}};
        for (Object[] s : sv) { JPanel c = card(); c.add(lbl((String) s[0], Font.BOLD, 22, (Color) s[2]), BorderLayout.CENTER); c.add(lbl((String) s[1], Font.PLAIN, 11, MUTED), BorderLayout.SOUTH); stats.add(c); }
        put(p.body, stats); gap(p.body, 8);
        JPanel chips = row(); for (String x : new String[]{"All", "Earned", "In progress", "Locked"}) chips.add(chip(x, x.equals(certFilter), () -> { certFilter = x; show("Certs"); })); put(p.body, chips); gap(p.body, 6);
        for (String[] b : bs) {
            if (!certFilter.equals("All") && !b[2].equals(certFilter)) continue; boolean ok = b[2].equals("Earned"); Color sc = ok ? GREEN : b[2].equals("In progress") ? GOLD : MUTED;
            JPanel c = card(); c.add(swatchWrap(sc, 46), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row(); h.add(lbl(b[0], Font.BOLD, 14, TXT)); h.add(tag(b[2].toUpperCase(), sc)); put(inf, h);
            put(inf, lbl(ok ? "Verified by SkillSwap Peer Learning Office" : b[1], Font.PLAIN, 12, MUTED));
            put(inf, lbl(ok ? "Issued " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMM yyyy")) : "Progress: " + b[3], Font.PLAIN, 11, MUTED)); c.add(inf);
            if (ok) { JPanel r = row(); r.add(ghost("View", () -> info("CERTIFICATE OF ACHIEVEMENT\n\n" + name(me) + "\nhas earned: " + b[0] + "\n" + b[1] + "\n\nIssued " + LocalDate.now() + " by SkillSwap Campus")));
                r.add(ghost("Share", () -> { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(name(me) + " earned " + b[0] + " on SkillSwap Campus"), null); toast("Copied to clipboard"); })); c.add(r, BorderLayout.EAST); }
            put(p.body, c); gap(p.body, 8);
        }
        return p.root;
    }

    // ====================== BOOKINGS ======================
    static void book(String teacher, String learner, String skill) {
        if (learner.equals(me.username) && me.credits < 1) { info("You need at least 1 swap credit to request a session.\nTeach a session to earn credits!"); return; }
        JComboBox<String> day = combo(new String[7]), time = combo(new String[10]), mode = combo(new String[]{"Online", "In person"}); List<LocalDate> days = new ArrayList<>();
        DefaultComboBoxModel<String> dm = new DefaultComboBoxModel<>(); for (int i = 0; i < 7; i++) { LocalDate d = LocalDate.now().plusDays(i); days.add(d); dm.addElement(d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))); } day.setModel(dm);
        DefaultComboBoxModel<String> tm = new DefaultComboBoxModel<>(); for (int h = 9; h < 19; h++) tm.addElement(LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h:mm a"))); time.setModel(tm);
        if (!ask("Book: " + skill + " (" + name(teacher) + " teaching, " + name(learner) + " learning)", form(new String[]{"Day", "Time", "Mode"}, new JComponent[]{day, time, mode}))) return;
        Sess s = new Sess(); s.id = nextId(); s.teacher = teacher; s.learner = learner; s.skill = skill; s.mode = String.valueOf(mode.getSelectedItem()); s.status = "PENDING"; s.requester = me.username;
        s.at = LocalDateTime.of(days.get(day.getSelectedIndex()), LocalTime.of(9 + time.getSelectedIndex(), 0)); S.sessions.add(s); save();
        String o = teacher.equals(me.username) ? learner : teacher; toast("Request sent to " + name(o));
        if (S.users.get(o).demo) {   // demo accounts auto-accept after a moment; look the session up again by id in case data was refreshed meanwhile
            final int sid = s.id;
            Timer t = new Timer(2500, e -> { Sess cur = sess(sid);
                if (cur != null && cur.status.equals("PENDING")) { if (confirm(cur)) notify("Swap accepted", name(o) + " accepted your " + cur.skill + " session"); else { cur.status = "DECLINED"; save(); } if (me != null) show(page); } });
            t.setRepeats(false); t.start();
        }
        bookTab = "Upcoming"; show("Bookings");
    }
    static boolean confirm(Sess s) {
        User l = S.users.get(s.learner); if (l.credits < 1) return false; l.credits--; s.status = "CONFIRMED"; save(); return true;
    }
    static void rate(Sess s) {
        JComboBox<String> r = combo(new String[]{"5 - Excellent", "4 - Good", "3 - OK", "2 - Poor", "1 - Bad"}); JTextArea t = new JTextArea(3, 22); t.setBackground(PANEL2); t.setForeground(TXT); t.setCaretColor(TXT);
        if (!ask("Rate your session with " + name(s.teacher), form(new String[]{"Rating", "Feedback"}, new JComponent[]{r, new JScrollPane(t)}))) return;
        s.rating = 5 - r.getSelectedIndex(); s.review = t.getText().trim(); save(); toast("Thanks for your feedback!");
    }
    static void complete(Sess s) {
        s.status = "COMPLETED"; User t = S.users.get(s.teacher); t.credits = Math.min(MAX_CREDITS, t.credits + 1); save();
        if (s.learner.equals(me.username)) rate(s); else toast("Session logged. +1 credit earned!"); show("Bookings");
    }
    static JComponent bookingsPage() {
        Pg p = pg("Bookings & Sessions", "Manage your tutoring and skill-swap sessions", btn("+ New Booking", () -> {
            List<String> opts = new ArrayList<>(); for (User u : S.users.values()) if (u != me) for (String s : u.teach) opts.add(name(u) + " - " + s + "|" + u.username + "|" + s);
            if (opts.isEmpty()) return; JComboBox<String> cb = combo(opts.stream().map(x -> x.split("\\|")[0]).toArray(String[]::new));
            if (ask("Choose a tutor", form(new String[]{"Tutor & skill"}, new JComponent[]{cb}))) { String[] x = opts.get(cb.getSelectedIndex()).split("\\|"); book(x[1], me.username, x[2]); } }));
        JPanel days = row(); LocalDate td = LocalDate.now();
        for (int i = 0; i < 7; i++) { LocalDate d = td.plusDays(i); long n = S.sessions.stream().filter(s -> s.status.equals("CONFIRMED") && involves(s, me) && s.at.toLocalDate().equals(d)).count(); boolean on = d.equals(dayFilter);
            days.add(chip(d.format(DateTimeFormatter.ofPattern("EEE d")) + (n > 0 ? " \u2022" + n : ""), on, () -> { dayFilter = on ? null : d; show("Bookings"); })); }
        put(p.body, days);
        List<Sess> mine = S.sessions.stream().filter(s -> involves(s, me) && (dayFilter == null || s.at.toLocalDate().equals(dayFilter))).sorted(Comparator.comparing((Sess s) -> s.at)).collect(Collectors.toList());
        List<Sess> inc = mine.stream().filter(s -> s.status.equals("PENDING") && !s.requester.equals(me.username)).collect(Collectors.toList());
        long up = mine.stream().filter(s -> s.status.equals("CONFIRMED") || (s.status.equals("PENDING") && s.requester.equals(me.username))).count();
        JPanel chips = row(); for (String t : new String[]{"Upcoming", "Past", "Requests"}) chips.add(chip(t + (t.equals("Requests") ? " (" + inc.size() + ")" : t.equals("Upcoming") ? " (" + up + ")" : ""), t.equals(bookTab), () -> { bookTab = t; show("Bookings"); })); put(p.body, chips); gap(p.body, 6);
        List<Sess> shown = mine.stream().filter(s -> bookTab.equals("Upcoming") ? (s.status.equals("CONFIRMED") || (s.status.equals("PENDING") && s.requester.equals(me.username)))
                : bookTab.equals("Past") ? (s.status.equals("COMPLETED") || s.status.equals("CANCELLED") || s.status.equals("DECLINED")) : inc.contains(s)).collect(Collectors.toList());
        if (bookTab.equals("Past")) Collections.reverse(shown);
        if (shown.isEmpty()) put(p.body, lbl("Nothing here yet. Book a tutor from the Skill Feed or Search Topics.", Font.PLAIN, 12, MUTED));
        for (Sess s : shown) { put(p.body, sessionCard(s)); gap(p.body, 8); }
        return p.root;
    }
    static JComponent sessionCard(Sess s) {
        String o = other(s); boolean iLearn = s.learner.equals(me.username);
        JPanel c = card(); c.add(avatarWrap(name(o), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
        h.add(lbl(name(o), Font.BOLD, 13, TXT)); h.add(tag(s.status, s.status.equals("CONFIRMED") || s.status.equals("COMPLETED") ? GREEN : s.status.equals("PENDING") ? GOLD : MUTED)); h.add(tag(iLearn ? "YOU LEARN" : "YOU TEACH", BLUE)); put(inf, h);
        put(inf, lbl(s.skill + " \u2022 1-on-1 \u2022 " + s.at.format(DF) + " \u2022 " + s.mode, Font.PLAIN, 12, MUTED));
        if (s.status.equals("COMPLETED") && iLearn) put(inf, lbl(s.rating > 0 ? "Your rating: " + stars(s.rating) : "Not rated yet", Font.PLAIN, 11, GOLD));
        c.add(inf); JPanel b = row(); b.setPreferredSize(new Dimension(230, 60));
        switch (s.status) {
            case "PENDING":
                if (s.requester.equals(me.username)) b.add(ghost("Cancel request", () -> { s.status = "CANCELLED"; save(); show("Bookings"); }));
                else { b.add(btn("Accept", () -> { if (confirm(s)) notify("Swap accepted", "Session with " + name(o) + " confirmed"); else info(name(s.learner) + " has no swap credits left."); show("Bookings"); }));
                    b.add(ghost("Decline", () -> { s.status = "DECLINED"; save(); show("Bookings"); })); } break;
            case "CONFIRMED":
                b.add(btn("Join", () -> info("Session room: SS-" + (1000 + s.id) + "\n" + (s.mode.equals("Online") ? "Open the campus meeting room with this code." : "Meet in person at the agreed campus spot."))));
                b.add(ghost("Complete", () -> complete(s))); b.add(ghost("Reschedule", () -> reschedule(s)));
                b.add(ghost("Cancel", () -> { User l = S.users.get(s.learner); l.credits = Math.min(MAX_CREDITS, l.credits + 1); s.status = "CANCELLED"; save(); show("Bookings"); })); break;
            case "COMPLETED": if (iLearn && s.rating == 0) b.add(btn("Rate", () -> { rate(s); show("Bookings"); })); break;
            default: }
        b.add(ghost("\u2691", () -> report(o))); c.add(b, BorderLayout.EAST); return c;
    }
    static void reschedule(Sess s) {
        JComboBox<String> day = combo(new String[0]), time = combo(new String[0]); List<LocalDate> days = new ArrayList<>(); DefaultComboBoxModel<String> dm = new DefaultComboBoxModel<>();
        for (int i = 0; i < 7; i++) { LocalDate d = LocalDate.now().plusDays(i); days.add(d); dm.addElement(d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))); } day.setModel(dm);
        DefaultComboBoxModel<String> tm = new DefaultComboBoxModel<>(); for (int h = 9; h < 19; h++) tm.addElement(LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h:mm a"))); time.setModel(tm);
        if (!ask("Reschedule " + s.skill, form(new String[]{"New day", "New time"}, new JComponent[]{day, time}))) return;
        s.at = LocalDateTime.of(days.get(day.getSelectedIndex()), LocalTime.of(9 + time.getSelectedIndex(), 0)); remindedIds.remove(s.id); save(); toast("Session rescheduled"); show("Bookings");
    }

    // ====================== NEARBY NOW ======================
    static JComponent nearbyPage() {
        Pg p = pg("Nearby Now", "Students currently open to an impromptu swap", null);
        JCheckBox me2 = check("I'm open to an impromptu swap right now", me.nearby); me2.addActionListener(e -> { me.nearby = me2.isSelected(); save(); show("Nearby"); });
        JPanel c0 = card(); c0.add(me2); put(p.body, c0); gap(p.body, 8); int n = 0;
        for (User u : S.users.values()) if (u != me && u.nearby) { n++; JPanel c = card(); c.add(avatarWrap(name(u), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
            h.add(lbl(name(u), Font.BOLD, 13, TXT)); h.add(tag("\u25CF ONLINE", GREEN)); put(inf, h); put(inf, lbl("Teaches: " + String.join(", ", u.teach) + "   \u2022   Wants: " + String.join(", ", u.learn), Font.PLAIN, 12, MUTED)); c.add(inf);
            JPanel b = row(); if (!u.teach.isEmpty()) b.add(btn("SWAP NOW", () -> book(u.username, me.username, u.teach.get(0)))); b.add(ghost("Message", () -> { chatWith = u.username; show("Messages"); })); c.add(b, BorderLayout.EAST); put(p.body, c); gap(p.body, 8); }
        if (n == 0) put(p.body, lbl("Nobody else is open right now. Check back soon!", Font.PLAIN, 12, MUTED));
        return p.root;
    }

    // ====================== MESSAGES ======================
    static void sendMsg(String to, String text) {
        Msg m = new Msg(); m.id = nextId(); m.from = me.username; m.to = to; m.text = text; m.time = System.currentTimeMillis(); S.msgs.add(m); save();
        User o = S.users.get(to);
        if (o != null && o.demo) { typingFrom = to; String[] rp = {"Sounds good!", "Sure - when are you free?", "Happy to swap skills with you!", "Let me check my schedule and get back to you.", "Great, send me a booking request."};
            Timer t = new Timer(1500, e -> { typingFrom = null; Msg r = new Msg(); r.id = nextId(); r.from = to; r.to = m.from; r.text = rp[new Random().nextInt(rp.length)]; r.time = System.currentTimeMillis(); lastMsgSeen = Math.max(lastMsgSeen, r.time); S.msgs.add(r); save();
                if (me != null && page.equals("Messages") && to.equals(chatWith)) show("Messages"); else if (me != null) notify("New message", name(to) + ": " + r.text); }); t.setRepeats(false); t.start(); }
    }
    static final Color[] RING = {new Color(0xC62828), new Color(0x5C8DF0), new Color(0xE91E63), new Color(0x5B3A86), new Color(0xC036D8), new Color(0x26A69A), new Color(0xFF9800), new Color(0x8BC34A)};
    static Color ring(String un) { return RING[Math.floorMod(un.hashCode(), RING.length)]; }

    /** Round profile picture with a coloured ring and a person silhouette. */
    static JComponent person(Color ring, int sz) {
        return new JComponent() {
            { Dimension d = new Dimension(sz, sz); setPreferredSize(d); setMinimumSize(d); setMaximumSize(d); }
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); aa(g); int rw = Math.max(3, sz / 16);
                g.setColor(ring); g.fillOval(0, 0, sz, sz);
                g.setColor(dark ? new Color(0xA3A9C6) : new Color(0xC9CEE4)); g.fillOval(rw, rw, sz - 2 * rw, sz - 2 * rw);
                g.setClip(new java.awt.geom.Ellipse2D.Float(rw, rw, sz - 2 * rw, sz - 2 * rw)); g.setColor(new Color(0x10173A));
                int hr = Math.round(sz * 0.17f); g.fillOval(sz / 2 - hr, Math.round(sz * 0.22f), hr * 2, hr * 2);
                g.fillOval(Math.round(sz * 0.2f), Math.round(sz * 0.58f), Math.round(sz * 0.6f), Math.round(sz * 0.6f)); g.dispose();
            }
        };
    }
    /** Rounded search / input pill. */
    static JTextField pill(String hint, boolean icon) {
        JTextField t = new JTextField() {
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); aa(g); int w = getWidth() - 1, h = getHeight() - 1;
                g.setColor(dark ? new Color(0x2B2F45) : PANEL2); g.fillRoundRect(0, 0, w, h, h, h); g.setColor(dark ? Color.BLACK : new Color(0xC3CAE0)); g.drawRoundRect(0, 0, w, h, h, h);
                if (icon) { g.setColor(TXT); g.setStroke(new BasicStroke(2f)); g.drawOval(13, h / 2 - 8, 11, 11); g.drawLine(22, h / 2 + 2, 27, h / 2 + 7); }
                if (getText().isEmpty()) { g.setColor(icon ? TXT : MUTED); g.setFont(getFont()); g.drawString(hint, icon ? 36 : 18, (h + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2); }
                g.dispose(); super.paintComponent(g0);
            }
        };
        t.setOpaque(false); t.setForeground(TXT); t.setCaretColor(TXT); t.setFont(f(Font.PLAIN, 14)); t.setBorder(new EmptyBorder(10, icon ? 36 : 18, 10, 14)); return t;
    }
    static JPanel bubble(JComponent content, Color bg, int vpad, int hpad, int arc) {
        JPanel b = new JPanel(new BorderLayout()) {
            protected void paintComponent(Graphics g0) { Graphics2D g = (Graphics2D) g0.create(); aa(g); g.setColor(getBackground()); g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc); g.dispose(); }
        };
        b.setOpaque(false); b.setBackground(bg); b.setBorder(new EmptyBorder(vpad, hpad, vpad, hpad)); b.add(content); return b;
    }
    static JLabel wrapLabel(String text, Color c, int size) {
        String h = esc(text); JLabel l = lbl("<html>" + h + "</html>", Font.PLAIN, size, c);
        if (l.getPreferredSize().width > 340) l.setText("<html><body style='width:340px'>" + h + "</body></html>"); return l;
    }
    static JComponent hline() {
        return new JComponent() { { setPreferredSize(new Dimension(10, 3)); }
            protected void paintComponent(Graphics g) { g.setColor(dark ? new Color(0xB8BFE0) : new Color(0xB5BDD6)); g.fillRect(0, getHeight() / 2, getWidth(), 1); } };
    }
    static JComponent dayDivider(String text) {
        JPanel p = new JPanel(new GridBagLayout()); p.setOpaque(false); p.setBorder(new EmptyBorder(4, 0, 10, 0)); GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; g.gridy = 0; g.gridx = 0; p.add(hline(), g);
        g.gridx = 1; g.weightx = 0; g.insets = new Insets(0, 14, 0, 14); p.add(lbl(text, Font.BOLD, 11, TXT), g);
        g.gridx = 2; g.weightx = 1; g.insets = new Insets(0, 0, 0, 0); p.add(hline(), g); return p;
    }
    static String dayLabel(LocalDate d) {
        LocalDate t = LocalDate.now(); return d.equals(t) ? "TODAY" : d.equals(t.minusDays(1)) ? "YESTERDAY" : d.format(DateTimeFormatter.ofPattern("MMM d, yyyy")).toUpperCase();
    }
    static String whenText(LocalDateTime at) {
        LocalDate t = LocalDate.now(); String tm = at.format(DateTimeFormatter.ofPattern("h:mm a"));
        return at.toLocalDate().equals(t) ? "Today, " + tm : at.toLocalDate().equals(t.plusDays(1)) ? "Tomorrow, " + tm : at.format(DF);
    }
    static JComponent bubbleRow(String text, boolean mine, long time) {
        JPanel b = bubble(wrapLabel(text, mine ? ONPINK : TXT, 14), mine ? PINK : (dark ? PANEL : PANEL2), 12, 18, 24);
        b.setToolTipText(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, h:mm a")));
        JPanel r = new JPanel(new BorderLayout()); r.setOpaque(false); r.setBorder(new EmptyBorder(6, 0, 6, 0)); r.add(b, mine ? BorderLayout.EAST : BorderLayout.WEST); return r;
    }
    static JComponent sessionBubble(Sess cs) {
        JPanel c = new JPanel(new BorderLayout()) {
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); aa(g); g.setColor(dark ? PANEL : PANEL2); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g.setColor(GREEN); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16); g.dispose();
            }
        };
        c.setOpaque(false); c.setBorder(new EmptyBorder(8, 8, 8, 10)); JPanel in = vbox();
        put(in, lbl("Session Confirmed", Font.BOLD, 13, GREEN)); gap(in, 6); put(in, lbl(cs.skill + " " + whenText(cs.at), Font.PLAIN, 14, MUTED)); put(in, lbl(cs.mode, Font.PLAIN, 14, MUTED));
        JButton join = btn("Join", () -> info("Session room: SS-" + (1000 + cs.id) + "\n" + (cs.mode.equals("Online") ? "Open the campus meeting room with this code." : "Meet in person at the agreed campus spot.")));
        join.setFont(f(Font.BOLD, 10)); join.setBorder(new EmptyBorder(5, 14, 5, 14));
        JPanel e = new JPanel(new BorderLayout()); e.setOpaque(false); e.add(join, BorderLayout.SOUTH); c.add(in); c.add(e, BorderLayout.EAST); c.setPreferredSize(new Dimension(345, 104));
        JPanel r = new JPanel(new BorderLayout()); r.setOpaque(false); r.setBorder(new EmptyBorder(8, 0, 8, 0)); r.add(c, BorderLayout.WEST); return r;
    }
    static JComponent convoRow(User u, long last, boolean sel) {
        Color rowBg = sel ? (dark ? new Color(0x10173A) : PANEL2) : leftBg();
        JPanel r = new JPanel(new BorderLayout(10, 0)); r.setBackground(rowBg); r.setBorder(new EmptyBorder(10, 0, 10, 14)); r.setCursor(new Cursor(Cursor.HAND_CURSOR));
        JPanel bar = new JPanel(); bar.setPreferredSize(new Dimension(5, 0)); bar.setBackground(sel ? PINK : rowBg);
        JPanel av = new JPanel(new GridBagLayout()); av.setOpaque(false); av.add(person(ring(u.username), 62));
        JPanel west = new JPanel(new BorderLayout(10, 0)); west.setOpaque(false); west.add(bar, BorderLayout.WEST); west.add(av);
        JPanel mid = new JPanel(new GridBagLayout()); mid.setOpaque(false); GridBagConstraints g = new GridBagConstraints(); g.anchor = GridBagConstraints.WEST; g.weightx = 1;
        mid.add(lbl(name(u), Font.PLAIN, 16, sel ? MUTED : TXT), g);
        r.add(west, BorderLayout.WEST); r.add(mid);
        if (last > 0) { JPanel e = new JPanel(new BorderLayout()); e.setOpaque(false); e.add(lbl(tshort(last), Font.BOLD, 12, TXT), BorderLayout.NORTH); r.add(e, BorderLayout.EAST); }
        String un = u.username; r.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent ev) { chatWith = un; draft = ""; show("Messages"); } });
        return r;
    }
    static JComponent messagesPage() {
        JPanel rt = new JPanel(new BorderLayout()); rt.setBackground(BG);
        Map<String, Long> lastT = new HashMap<>();
        for (Msg m : S.msgs) { if (m.from.equals(me.username)) lastT.merge(m.to, m.time, Math::max); else if (m.to.equals(me.username)) lastT.merge(m.from, m.time, Math::max); }
        List<User> others = S.users.values().stream().filter(u -> u != me).sorted((x, y) -> { int k = Long.compare(lastT.getOrDefault(y.username, 0L), lastT.getOrDefault(x.username, 0L)); return k != 0 ? k : name(x).compareToIgnoreCase(name(y)); }).collect(Collectors.toList());
        if ((chatWith == null || !S.users.containsKey(chatWith)) && !others.isEmpty()) chatWith = others.get(0).username;

        // ---- left: title, search pill, conversation list ----
        JPanel left = new JPanel(new BorderLayout()); left.setBackground(leftBg()); left.setPreferredSize(new Dimension(300, 0)); left.setBorder(new MatteBorder(0, 0, 0, 1, divider()));
        JPanel top = vbox(); top.setBorder(new EmptyBorder(18, 16, 14, 16));
        put(top, lbl("Messages", Font.BOLD, 34, TXT)); gap(top, 8);
        JTextField q = pill("Search chats", true); q.setText(chatQ); q.addActionListener(e -> { chatQ = q.getText().trim(); show("Messages"); }); put(top, q);
        left.add(top, BorderLayout.NORTH);
        JPanel list = vbox();
        for (User u : others) { if (!chatQ.isEmpty() && !name(u).toLowerCase().contains(chatQ.toLowerCase())) continue; put(list, convoRow(u, lastT.getOrDefault(u.username, 0L), u.username.equals(chatWith))); }
        JPanel lw = new JPanel(new BorderLayout()); lw.setOpaque(false); lw.add(list, BorderLayout.NORTH);
        JScrollPane ls = new JScrollPane(lw, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        ls.setBorder(null); ls.getViewport().setBackground(leftBg()); ls.getVerticalScrollBar().setUnitIncrement(18); left.add(ls);
        rt.add(left, BorderLayout.WEST);

        // ---- right: chat ----
        JPanel right = new JPanel(new BorderLayout()); right.setBackground(BG);
        if (chatWith == null || !S.users.containsKey(chatWith)) {
            JPanel e = new JPanel(new GridBagLayout()); e.setOpaque(false); e.add(lbl("Select a conversation to start chatting", Font.PLAIN, 14, MUTED)); right.add(e);
        } else {
            final User o = S.users.get(chatWith);
            JPanel h = new JPanel(new BorderLayout(14, 0)); h.setBackground(BG); h.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, divider()), new EmptyBorder(14, 26, 14, 26)));
            JPanel hav = new JPanel(new GridBagLayout()); hav.setOpaque(false); hav.add(person(ring(o.username), 58));
            JPanel hi = new JPanel(new GridBagLayout()); hi.setOpaque(false); JPanel hv = vbox();
            put(hv, lbl(name(o), Font.BOLD, 20, TXT)); put(hv, lbl("\u25CF " + (o.nearby ? "Online" : "Offline"), Font.PLAIN, 16, o.nearby ? GREEN : MUTED)); GridBagConstraints g = new GridBagConstraints(); g.anchor = GridBagConstraints.WEST; g.weightx = 1; hi.add(hv, g);
            JPanel hb = new JPanel(new GridBagLayout()); hb.setOpaque(false); JPanel hbr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); hbr.setOpaque(false);
            hbr.add(ghost("\u2691", () -> report(o.username)));
            if (!o.teach.isEmpty()) { JButton bk = btn("Book Session", () -> book(o.username, me.username, o.teach.get(0))); bk.setFont(f(Font.BOLD, 16)); bk.setBorder(new EmptyBorder(10, 18, 10, 18)); hbr.add(bk); }
            hb.add(hbr); h.add(hav, BorderLayout.WEST); h.add(hi); h.add(hb, BorderLayout.EAST); right.add(h, BorderLayout.NORTH);

            JPanel msgs = vbox(); msgs.setBorder(new EmptyBorder(16, 30, 16, 30));
            List<Msg> conv = S.msgs.stream().filter(m -> (m.from.equals(me.username) && m.to.equals(o.username)) || (m.from.equals(o.username) && m.to.equals(me.username))).collect(Collectors.toList());
            LocalDate lastDay = null;
            for (Msg m : conv) {
                LocalDate d = Instant.ofEpochMilli(m.time).atZone(ZoneId.systemDefault()).toLocalDate();
                if (!d.equals(lastDay)) { put(msgs, dayDivider(dayLabel(d))); lastDay = d; }
                put(msgs, bubbleRow(m.text, m.from.equals(me.username), m.time));
            }
            if (conv.isEmpty()) { put(msgs, dayDivider("TODAY")); put(msgs, lbl("No messages yet - say hi to " + name(o) + "!", Font.PLAIN, 13, MUTED)); }
            Sess cs = S.sessions.stream().filter(s -> s.status.equals("CONFIRMED") && involves(s, me) && involves(s, o)).reduce((x, y) -> y).orElse(null);
            if (cs != null) put(msgs, sessionBubble(cs));
            if (typingFrom != null && typingFrom.equals(o.username)) {
                JLabel ty = lbl("\u2022  \u2022  \u2022", Font.BOLD, 14, MUTED); JPanel tp = new JPanel(new BorderLayout()); tp.setOpaque(false); tp.setBorder(new EmptyBorder(6, 0, 6, 0));
                tp.add(bubble(ty, dark ? PANEL : PANEL2, 6, 14, 20), BorderLayout.WEST); put(msgs, tp);
            }
            JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false); wrap.add(msgs, BorderLayout.NORTH);
            JScrollPane sp = new JScrollPane(wrap); sp.setBorder(null); sp.getViewport().setBackground(BG); sp.getVerticalScrollBar().setUnitIncrement(18); right.add(sp);
            Timer st = new Timer(40, e -> sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum())); st.setRepeats(false); st.start();

            JTextField in = pill("Type a message...", false); in.setText(draft);
            in.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { draft = in.getText(); } public void removeUpdate(javax.swing.event.DocumentEvent e) { draft = in.getText(); } public void changedUpdate(javax.swing.event.DocumentEvent e) { draft = in.getText(); } });
            Runnable send = () -> { String t = in.getText().trim(); if (!t.isEmpty()) { draft = ""; sendMsg(o.username, t); show("Messages"); } }; in.addActionListener(e -> send.run());
            SwingUtilities.invokeLater(() -> { in.requestFocusInWindow(); in.setCaretPosition(in.getText().length()); });
            JButton sendB = btn("Send", send); sendB.setFont(f(Font.BOLD, 14)); sendB.setBorder(new EmptyBorder(10, 22, 10, 22));
            JPanel bar = new JPanel(new BorderLayout(10, 0)); bar.setBackground(BG); bar.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, divider()), new EmptyBorder(12, 26, 12, 26))); bar.add(in); bar.add(sendB, BorderLayout.EAST); right.add(bar, BorderLayout.SOUTH);
        }
        rt.add(right); return rt;
    }

    // ====================== PROFILE ======================
    static JComponent profilePage() {
        Pg p = pg("My Profile & Rating", me.email, btn("Edit Profile", SkillSwapCampus::editProfile));
        JPanel hd = card(); hd.add(avatarWrap(name(me), 64), BorderLayout.WEST); JPanel inf = vbox(); JPanel nm = row(); nm.add(lbl(name(me), Font.BOLD, 18, TXT)); if (verified(me)) nm.add(tag("VERIFIED TUTOR", GREEN));
        nm.add(tag(me.publicRatings ? "RATINGS PUBLIC" : "RATINGS PRIVATE", MUTED)); put(inf, nm); put(inf, lbl(me.bio.isEmpty() ? "Add your year & course with Edit Profile" : me.bio, Font.PLAIN, 12, MUTED));
        put(inf, lbl(me.useRealName ? "Showing real name (username: " + me.username + ")" : "Showing username" + (me.realName.isEmpty() ? "" : " (real name hidden)"), Font.PLAIN, 11, MUTED)); hd.add(inf); put(p.body, hd); gap(p.body, 8);
        JPanel stats = new JPanel(new GridLayout(1, 3, 8, 0)); stats.setOpaque(false);
        long sess = S.sessions.stream().filter(s -> s.status.equals("COMPLETED") && involves(s, me)).count();
        String[][] sv = {{"" + sess, "Sessions"}, {"" + reviews(me).size(), "Reviews"}, {"" + learnedN(me), "Skill swaps"}};
        for (String[] s : sv) { JPanel c = card(); c.add(lbl(s[0], Font.BOLD, 26, TXT), BorderLayout.CENTER); c.add(lbl(s[1], Font.PLAIN, 11, MUTED), BorderLayout.SOUTH); stats.add(c); } put(p.body, stats); gap(p.body, 10);
        put(p.body, skillRow("Skills I teach", me.teach, GREEN)); gap(p.body, 6); put(p.body, skillRow("Want to learn", me.learn, GOLD)); gap(p.body, 8);
        JPanel acts = row(); acts.add(ghost("Sync ratings online", SkillSwapCampus::sync)); JCheckBox nb = check("Open to impromptu swap now", me.nearby); nb.addActionListener(e -> { me.nearby = nb.isSelected(); save(); }); acts.add(nb); put(p.body, acts); gap(p.body, 8);
        put(p.body, lbl("Recently reviewed", Font.BOLD, 13, MUTED)); gap(p.body, 4); List<Sess> rs = reviews(me);
        if (rs.isEmpty()) put(p.body, lbl("No reviews yet - complete a session to receive feedback.", Font.PLAIN, 12, MUTED));
        for (int i = rs.size() - 1; i >= 0; i--) { Sess s = rs.get(i); JPanel c = card(); c.add(avatarWrap(name(s.learner), 32), BorderLayout.WEST); JPanel iv = vbox(); put(iv, lbl(name(s.learner), Font.BOLD, 12, TXT)); put(iv, lbl(s.skill + (s.review.isEmpty() ? "" : " - " + s.review), Font.PLAIN, 12, MUTED)); c.add(iv); c.add(lbl(stars(s.rating), Font.PLAIN, 12, GOLD), BorderLayout.EAST); put(p.body, c); gap(p.body, 5); }
        return p.root;
    }
    static JComponent skillRow(String title, List<String> list, Color col) {
        JPanel c = card(); c.add(lbl(title, Font.BOLD, 12, TXT), BorderLayout.NORTH); JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); r.setOpaque(false);
        for (String s : new ArrayList<>(list)) { JButton b = chip(s + "  \u00D7", false, () -> { list.remove(s); save(); show("Profile"); }); b.setForeground(col); b.setToolTipText("Click to remove"); r.add(b); }
        r.add(btn("+ Add", () -> { JComboBox<String> cb = combo(CAT.keySet().stream().map(x -> x.substring(0, 1).toUpperCase() + x.substring(1)).toArray(String[]::new)); cb.setEditable(true);
            if (ask("Add skill", form(new String[]{"Skill"}, new JComponent[]{cb}))) { String s = String.valueOf(cb.getSelectedItem()).trim(); if (!s.isEmpty() && list.stream().noneMatch(x -> x.equalsIgnoreCase(s))) { list.add(s); save(); show("Profile"); } } }));
        c.add(r); return c;
    }
    static void editProfile() {
        JTextField rn = field(); rn.setText(me.realName); JTextArea bio = new JTextArea(3, 22); bio.setText(me.bio); bio.setLineWrap(true); bio.setBackground(PANEL2); bio.setForeground(TXT); bio.setCaretColor(TXT);
        JCheckBox real = check("Show my real name publicly", me.useRealName), pub = check("Make my ratings public", me.publicRatings);
        if (!ask("Edit profile", form(new String[]{"Real name", "Bio / major", "", ""}, new JComponent[]{rn, new JScrollPane(bio), real, pub}))) return;
        me.realName = rn.getText().trim(); me.bio = bio.getText().trim(); me.useRealName = real.isSelected() && !me.realName.isEmpty(); me.publicRatings = pub.isSelected(); save(); show("Profile");
    }
    static void profileDialog(User u) {
        if (u == null) return; StringBuilder sb = new StringBuilder(name(u) + (verified(u) ? "  [VERIFIED]" : "") + "\n" + (u.bio.isEmpty() ? "" : u.bio + "\n") + "\nTeaches: " + String.join(", ", u.teach) + "\nWants to learn: " + String.join(", ", u.learn) + "\nSessions taught: " + taught(u) + "\n");
        if (u.publicRatings) { sb.append("Rating: ").append(reviews(u).isEmpty() ? "no reviews yet" : String.format("%.1f", avg(u)) + " (" + reviews(u).size() + " reviews)").append("\n");
            for (Sess s : reviews(u)) sb.append("  ").append(stars(s.rating)).append(" ").append(s.review).append("\n"); } else sb.append("Ratings are private.\n");
        info(sb.toString());
    }
    static void report(String target) {
        JComboBox<String> r = combo(new String[]{"No-show", "Inappropriate behavior", "Harassment", "Spam / fake profile", "Other"}); JTextArea d = new JTextArea(3, 22); d.setBackground(PANEL2); d.setForeground(TXT); d.setCaretColor(TXT);
        if (!ask("Report " + name(target), form(new String[]{"Reason", "Details"}, new JComponent[]{r, new JScrollPane(d)}))) return;
        Report rp = new Report(); rp.id = nextId(); rp.by = me.username; rp.target = target; rp.reason = String.valueOf(r.getSelectedItem()); rp.details = d.getText().trim(); rp.time = System.currentTimeMillis(); S.reports.add(rp); save();
        toast("Report submitted to campus moderators");
    }
    static void sync() {
        try { Files.createDirectories(DIR); StringBuilder j = new StringBuilder("{\"synced\":\"" + LocalDateTime.now() + "\",\"profiles\":[");
            boolean first = true; for (User u : S.users.values()) if (u.publicRatings && !u.demo) { if (!first) j.append(","); first = false;
                j.append("{\"name\":\"").append(name(u).replace("\"", "'")).append("\",\"rating\":").append(String.format("%.2f", avg(u))).append(",\"reviews\":").append(reviews(u).size()).append("}"); }
            j.append("]}"); Files.writeString(DIR.resolve("cloud-sync.json"), j.toString()); S.lastSync = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")); save();
            toast("Ratings & profile synced (simulated cloud: " + DIR.resolve("cloud-sync.json") + ")"); show(page);
        } catch (Exception e) { info("Offline - will sync later. Everything else keeps working against the database."); }
    }
}