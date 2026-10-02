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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.*;

/**
 * SkillSwap Campus - desktop peer skill-swap platform (Java Swing, no external libraries).
 * Compile:  javac SkillSwapCampus.java      Run:  java SkillSwapCampus
 * Demo accounts: password "demo" for ramos, alamag, henry, jeb, chinu, dino, jamesa, kylet. Or create your own.
 */
public class SkillSwapCampus {

    // ====================== MODEL (persisted locally) ======================
    static class User implements Serializable {
        String username, realName, email, passHash, bio = "";
        boolean useRealName, publicRatings = true, nearby, demo;
        List<String> teach = new ArrayList<>(), learn = new ArrayList<>();
        int credits = 5;
    }
    static class Post implements Serializable { int id; String author, type, skill, category, desc; long time; }
    static class Sess implements Serializable {
        int id, rating; String teacher, learner, skill, mode, status, requester, review = "";
        LocalDateTime at; boolean reminded;
    }
    static class Msg implements Serializable { String from, to, text; long time; }
    static class Report implements Serializable { String by, target, reason, details; long time; }
    static class Store implements Serializable {
        Map<String, User> users = new LinkedHashMap<>();
        List<Post> posts = new ArrayList<>(); List<Sess> sessions = new ArrayList<>();
        List<Msg> msgs = new ArrayList<>(); List<Report> reports = new ArrayList<>();
        int nextId = 1; String lastSync = "never";
    }
    static class Match { User u; List<String> theyTeach, iTeach; }

    static final int MAX_CREDITS = 10;
    static final Path DIR = Paths.get(System.getProperty("user.home"), ".skillswap");
    static final Path FILE = DIR.resolve("data.ser");
    static Store S; static User me;

    static void load() {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(FILE))) { S = (Store) in.readObject(); }
        catch (Exception e) { S = new Store(); seed(); save(); }
    }
    static void save() {
        try { Files.createDirectories(DIR);
            try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(FILE))) { o.writeObject(S); }
        } catch (Exception e) { /* offline storage best-effort */ }
    }
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
        Sess s = new Sess(); s.id = S.nextId++; s.teacher = t; s.learner = l; s.skill = skill; s.mode = "Online";
        s.status = "COMPLETED"; s.requester = l; s.rating = r; s.review = review; s.at = LocalDateTime.now().minusDays(5 + s.id); S.sessions.add(s);
    }
    static void post(String a, String type, String skill, String desc) {
        Post p = new Post(); p.id = S.nextId++; p.author = a; p.type = type; p.skill = skill; p.category = cat(skill);
        p.desc = desc; p.time = System.currentTimeMillis() - (long) (Math.random() * 6e6); S.posts.add(p);
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

    // ====================== THEME / UI HELPERS ======================
    static final Color BG = new Color(0x0B1026), PANEL = new Color(0x141B3B), PANEL2 = new Color(0x1E2755), PINK = new Color(0xE91E63),
            TXT = Color.WHITE, MUTED = new Color(0x8D96BD), GREEN = new Color(0x3DDC97), GOLD = new Color(0xFFC107), BLUE = new Color(0x6C8CFF);
    static Font f(int st, int sz) { return new Font("SansSerif", st, sz); }
    static JLabel lbl(String t, int st, int sz, Color c) { JLabel l = new JLabel(t); l.setFont(f(st, sz)); l.setForeground(c); return l; }
    static JPanel vbox() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setOpaque(false); return p; }
    static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4)); p.setOpaque(false); return p; }
    static void put(JPanel box, JComponent c) { c.setAlignmentX(0f); box.add(c); }
    static void gap(JPanel box, int h) { box.add(Box.createVerticalStrut(h)); }
    static JButton btn(String t, Runnable r) {
        JButton b = new JButton(t); b.setFont(f(Font.BOLD, 11)); b.setForeground(TXT); b.setBackground(PINK); b.setOpaque(true);
        b.setBorderPainted(false); b.setFocusPainted(false); b.setBorder(new EmptyBorder(6, 12, 6, 12)); b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> r.run()); return b;
    }
    static JButton ghost(String t, Runnable r) { JButton b = btn(t, r); b.setBackground(PANEL2); return b; }
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
                Graphics2D g = (Graphics2D) g0; g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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
        return JOptionPane.showConfirmDialog(frame, body, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }
    static void info(String m) { JOptionPane.showMessageDialog(frame, m, "SkillSwap Campus", JOptionPane.INFORMATION_MESSAGE); }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000; return m < 1 ? "just now" : m < 60 ? m + "m ago" : m < 1440 ? m / 60 + "h ago" : m / 1440 + "d ago";
    }
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
    static JFrame frame; static JPanel root, content, sidebar, rightPanel; static CardLayout rootCards = new CardLayout(); static JLabel statusBar;
    static String page = "Feed", feedFilter = "All", searchQ = "", searchCat = "All", bookTab = "Upcoming", chatWith = null, board = "Most Active", chatQ = "";
    static TrayIcon tray;
    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("EEE, MMM d \u2022 h:mm a");

    public static void main(String[] a) {
        UIManager.put("OptionPane.background", PANEL); UIManager.put("Panel.background", PANEL); UIManager.put("OptionPane.messageForeground", TXT);
        UIManager.put("Label.foreground", TXT); UIManager.put("ComboBox.selectionBackground", PINK);
        load();
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("SkillSwap Campus - Peer Learning"); frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { save(); } });
            root = new JPanel(rootCards); root.add(loginPanel(), "login"); root.add(appPanel(), "app");
            frame.setContentPane(root); frame.setSize(1280, 780); frame.setMinimumSize(new Dimension(1050, 650)); frame.setLocationRelativeTo(null);
            frame.setVisible(true); rootCards.show(root, "login");
            new Timer(30000, e -> checkReminders()).start();
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
        JWindow w = new JWindow(frame); JPanel p = new JPanel(new BorderLayout()); p.setBackground(PANEL2);
        p.setBorder(new CompoundBorder(new LineBorder(PINK, 1), new EmptyBorder(10, 14, 10, 14))); p.add(lbl("<html><body style='width:240px'>" + esc(t) + "</body></html>", Font.PLAIN, 12, TXT));
        w.setContentPane(p); w.pack(); Point o = frame.getLocationOnScreen();
        w.setLocation(o.x + frame.getWidth() - w.getWidth() - 20, o.y + frame.getHeight() - w.getHeight() - 50); w.setVisible(true);
        Timer x = new Timer(4000, e -> w.dispose()); x.setRepeats(false); x.start();
    }
    static void checkReminders() {
        if (me == null) return; LocalDateTime now = LocalDateTime.now();
        for (Sess s : S.sessions) if (s.status.equals("CONFIRMED") && !s.reminded && involves(s, me) && s.at.isAfter(now.minusMinutes(5)) && s.at.isBefore(now.plusMinutes(60))) {
            s.reminded = true; save(); notify("Upcoming session", s.skill + " with " + name(other(s)) + " at " + s.at.format(DateTimeFormatter.ofPattern("h:mm a")));
        }
    }
    static boolean involves(Sess s, User u) { return s.teacher.equals(u.username) || s.learner.equals(u.username); }
    static String other(Sess s) { return s.teacher.equals(me.username) ? s.learner : s.teacher; }

    // ====================== LOGIN ======================
    static JComponent iconTile(int type) {
        return new JComponent() {
            { setPreferredSize(new Dimension(74, 74)); }
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0; g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x2A2A2A)); g.fillRoundRect(0, 0, 73, 73, 8, 8); g.setColor(Color.WHITE); g.setStroke(new BasicStroke(2f));
                if (type == 2) { g.drawOval(20, 16, 28, 28); g.drawLine(44, 42, 56, 56); }
                else { g.drawPolygon(new int[]{36, 62, 36, 10}, new int[]{18, 30, 42, 30}, 4); g.drawArc(22, 36, 28, 18, 180, 180); g.drawLine(62, 30, 62, 44); }
            }
        };
    }
    static JComponent loginPanel() {
        JPanel bg = new JPanel(new GridBagLayout()); bg.setBackground(Color.BLACK);
        JPanel c = vbox(); c.setBackground(new Color(0x2B2B2B)); c.setOpaque(true); c.setBorder(new EmptyBorder(18, 28, 18, 28));
        JLabel title = lbl("LOGIN TO SKILLSWAP", Font.BOLD, 14, TXT); put(c, title); gap(c, 10);
        JPanel icons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0)); icons.setOpaque(false); for (int i = 0; i < 3; i++) icons.add(iconTile(i)); put(c, icons); gap(c, 12);
        JTextField u = field(); u.setToolTipText("Username or school email"); JPasswordField p = pass();
        put(c, lbl("Username or school email", Font.PLAIN, 11, MUTED)); put(c, u); gap(c, 6); put(c, lbl("Password", Font.PLAIN, 11, MUTED)); put(c, p); gap(c, 4);
        JButton forgot = ghost("Forgot Password?", SkillSwapCampus::forgot); forgot.setBackground(new Color(0x2B2B2B)); forgot.setForeground(MUTED);
        JPanel fr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); fr.setOpaque(false); fr.add(forgot); put(c, fr); gap(c, 6);
        Runnable go = () -> {
            String key = u.getText().trim(); String pw = new String(p.getPassword());
            User f = S.users.values().stream().filter(x -> x.username.equalsIgnoreCase(key) || x.email.equalsIgnoreCase(key)).findFirst().orElse(null);
            if (f == null || !f.passHash.equals(hash(f.username, pw))) { JOptionPane.showMessageDialog(frame, "Incorrect username/email or password.", "Sign in failed", JOptionPane.ERROR_MESSAGE); return; }
            u.setText(""); p.setText(""); enter(f);
        };
        p.addActionListener(e -> go.run()); u.addActionListener(e -> go.run());
        JButton si = btn("Sign in", go); si.setBorder(new EmptyBorder(9, 12, 9, 12)); si.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); put(c, si); gap(c, 6);
        JButton reg = ghost("Create account", SkillSwapCampus::register); reg.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); put(c, reg); gap(c, 8);
        put(c, lbl("Demo login: ramos / demo  (or create your own account)", Font.PLAIN, 10, MUTED));
        c.setPreferredSize(new Dimension(380, 460)); bg.add(c); return bg;
    }
    static void enter(User u) {
        me = u; page = "Feed"; rootCards.show(root, "app"); show("Feed"); checkReminders();
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
                : user.length() < 3 ? "Username must be at least 3 characters." : S.users.containsKey(user) || S.users.values().stream().anyMatch(x -> x.email.equals(email)) ? "That username or email is already registered."
                : pw.length() < 6 ? "Password must be at least 6 characters." : !pw.equals(new String(p2.getPassword())) ? "Passwords do not match." : null;
        if (err != null) { JOptionPane.showMessageDialog(frame, err, "Cannot create account", JOptionPane.WARNING_MESSAGE); return; }
        String code = String.format("%06d", new Random().nextInt(1000000)); notify("SkillSwap verification", "Your code is " + code);
        String in = JOptionPane.showInputDialog(frame, "Enter the 6-digit code sent to " + email + "\n(Demo: no mail server on the local network - your code is " + code + ")");
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
            {"Nearby", "Nearby Now"}, {"Messages", "Messages"}, {"Profile", "My Profile & Rating"}};
    static JComponent appPanel() {
        JPanel p = new JPanel(new BorderLayout()); p.setBackground(BG);
        sidebar = new JPanel(); sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS)); sidebar.setBackground(PANEL); sidebar.setPreferredSize(new Dimension(190, 0));
        content = new JPanel(new BorderLayout()); content.setBackground(BG);
        rightPanel = new JPanel(); rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS)); rightPanel.setBackground(PANEL); rightPanel.setPreferredSize(new Dimension(260, 0));
        statusBar = lbl(" ", Font.PLAIN, 11, MUTED); statusBar.setBorder(new EmptyBorder(3, 10, 3, 10)); JPanel sb = new JPanel(new BorderLayout()); sb.setBackground(PANEL); sb.add(statusBar);
        p.add(sidebar, BorderLayout.WEST); p.add(content, BorderLayout.CENTER); p.add(new JScrollPane(rightPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {{ setBorder(null); setPreferredSize(new Dimension(270, 0)); }}, BorderLayout.EAST);
        p.add(sb, BorderLayout.SOUTH); return p;
    }
    static void show(String pgName) {
        page = pgName; content.removeAll();
        JComponent c;
        switch (pgName) {
            case "Search": c = searchPage(); break; case "Certs": c = certsPage(); break; case "Bookings": c = bookingsPage(); break;
            case "Nearby": c = nearbyPage(); break; case "Messages": c = messagesPage(); break; case "Profile": c = profilePage(); break; default: c = feedPage();
        }
        content.add(c); content.revalidate(); content.repaint(); buildSidebar(); buildRight();
        statusBar.setText("Local network: connected   |   Signed in as " + name(me) + "   |   Credits: " + me.credits + "/" + MAX_CREDITS + "   |   Last online sync: " + S.lastSync);
    }
    static void buildSidebar() {
        sidebar.removeAll(); sidebar.setBorder(new EmptyBorder(14, 10, 12, 10));
        JPanel logo = vbox(); put(logo, lbl("SkillSwap", Font.BOLD, 17, PINK)); put(logo, lbl("Peer Learning", Font.PLAIN, 11, MUTED)); logo.setBorder(new EmptyBorder(0, 6, 14, 0)); put(sidebar, logo);
        for (String[] n : NAV) {
            boolean on = n[0].equals(page);
            JButton b = new JButton(n[1]); b.setHorizontalAlignment(SwingConstants.LEFT); b.setFont(f(Font.BOLD, 12)); b.setForeground(on ? TXT : MUTED);
            b.setBackground(on ? PINK : PANEL); b.setOpaque(true); b.setBorderPainted(false); b.setFocusPainted(false); b.setBorder(new EmptyBorder(8, 12, 8, 12));
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36)); b.setCursor(new Cursor(Cursor.HAND_CURSOR));
            String t = n[0]; b.addActionListener(e -> show(t)); put(sidebar, b); gap(sidebar, 4);
        }
        sidebar.add(Box.createVerticalGlue());
        JButton out = ghost("Log Out", () -> { save(); me = null; rootCards.show(root, "login"); }); out.setBackground(PANEL); out.setForeground(MUTED); put(sidebar, out);
        sidebar.revalidate(); sidebar.repaint();
    }
    static void buildRight() {
        rightPanel.removeAll(); rightPanel.setBorder(new EmptyBorder(14, 12, 12, 12));
        put(rightPanel, lbl("TOP TUTORS", Font.BOLD, 12, MUTED)); gap(rightPanel, 4);
        JPanel chips = row(); chips.setBorder(new EmptyBorder(0, 0, 0, 0));
        for (String bd : new String[]{"Most Active", "Most Learned"}) chips.add(chip(bd, bd.equals(board), () -> { board = bd; buildRight(); }));
        put(rightPanel, chips);
        List<User> us = new ArrayList<>(S.users.values());
        us.sort((x, y) -> board.equals("Most Active") ? Long.compare(taught(y), taught(x)) : Long.compare(learnedN(y), learnedN(x)));
        int rank = 1;
        for (User u : us.subList(0, Math.min(5, us.size()))) {
            JPanel r = card(); r.setBorder(new EmptyBorder(6, 8, 6, 8)); JPanel inf = vbox();
            JPanel nm = row(); nm.add(lbl(rank++ + ". " + name(u), Font.BOLD, 12, TXT)); if (verified(u)) nm.add(tag("\u2713", GREEN)); put(inf, nm);
            put(inf, lbl((board.equals("Most Active") ? taught(u) + " taught" : learnedN(u) + " learned") + (u.publicRatings && !reviews(u).isEmpty() ? "  " + String.format("%.1f", avg(u)) + "\u2605" : ""), Font.PLAIN, 11, MUTED));
            r.add(inf, BorderLayout.CENTER);
            if (u != me && !u.teach.isEmpty()) r.add(btn("Book", () -> book(u.username, me.username, u.teach.get(0))), BorderLayout.EAST);
            put(rightPanel, r); gap(rightPanel, 5);
        }
        gap(rightPanel, 10); put(rightPanel, lbl("CAMPUS BADGES", Font.BOLD, 12, MUTED)); gap(rightPanel, 4);
        JPanel cr = card(); cr.setBorder(new EmptyBorder(6, 8, 6, 8)); JPanel ci = vbox();
        put(ci, lbl("Swap credits: " + me.credits + " / " + MAX_CREDITS, Font.BOLD, 12, TXT));
        JProgressBar pb = new JProgressBar(0, MAX_CREDITS); pb.setValue(me.credits); pb.setForeground(PINK); pb.setBackground(PANEL2); pb.setBorderPainted(false); put(ci, pb);
        put(ci, lbl("Learning costs 1, teaching earns 1.", Font.PLAIN, 10, MUTED)); cr.add(ci); put(rightPanel, cr); gap(rightPanel, 5);
        for (String[] b : badges(me)) if (b[2].equals("Earned")) { JPanel br = card(); br.setBorder(new EmptyBorder(6, 8, 6, 8)); br.add(lbl("\u2605 " + b[0], Font.BOLD, 11, GOLD)); put(rightPanel, br); gap(rightPanel, 4); }
        rightPanel.add(Box.createVerticalGlue()); rightPanel.revalidate(); rightPanel.repaint();
    }

    // ====================== FEED ======================
    static JComponent feedPage() {
        Pg p = pg("Skill Feed", "Offers and requests from your campus", btn("+ New Post", SkillSwapCampus::newPost));
        JPanel chips = row(); for (String x : new String[]{"All", "Offers", "Requests", "Matches"}) chips.add(chip(x, x.equals(feedFilter), () -> { feedFilter = x; show("Feed"); }));
        put(p.body, chips); gap(p.body, 6);
        if (feedFilter.equals("Matches")) {
            List<Match> ms = matches();
            if (ms.isEmpty()) put(p.body, lbl("No matches yet - add skills you want to learn in My Profile & Rating.", Font.PLAIN, 12, MUTED));
            for (Match m : ms) { put(p.body, matchCard(m)); gap(p.body, 8); }
            return p.root;
        }
        List<Post> ps = new ArrayList<>(S.posts); Collections.reverse(ps);
        for (Post po : ps) {
            if (feedFilter.equals("Offers") && !po.type.equals("OFFER")) continue; if (feedFilter.equals("Requests") && !po.type.equals("REQUEST")) continue;
            put(p.body, postCard(po)); gap(p.body, 8);
        }
        return p.root;
    }
    static JComponent matchCard(Match m) {
        JPanel c = card(); c.add(avatarWrap(name(m.u), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
        h.add(lbl(name(m.u), Font.BOLD, 13, TXT)); h.add(m.iTeach.isEmpty() ? tag("CAN TEACH YOU", BLUE) : tag("PERFECT SWAP", GREEN)); if (verified(m.u)) h.add(tag("VERIFIED", GREEN)); put(inf, h);
        put(inf, lbl("Teaches you: " + String.join(", ", m.theyTeach) + (m.iTeach.isEmpty() ? "" : "   \u2022   Wants to learn from you: " + String.join(", ", m.iTeach)), Font.PLAIN, 12, MUTED));
        c.add(inf, BorderLayout.CENTER); JPanel b = row(); b.add(btn("REQUEST SWAP", () -> book(m.u.username, me.username, m.theyTeach.get(0)))); b.add(ghost("Message", () -> { chatWith = m.u.username; show("Messages"); }));
        c.add(b, BorderLayout.EAST); return c;
    }
    static JComponent postCard(Post po) {
        User a = S.users.get(po.author); boolean mine = po.author.equals(me.username); boolean offer = po.type.equals("OFFER");
        JPanel c = card(); c.add(avatarWrap(name(po.author), 40), BorderLayout.WEST); JPanel inf = vbox(); JPanel h = row();
        h.add(lbl(name(po.author), Font.BOLD, 13, TXT)); if (a != null && verified(a)) h.add(tag("VERIFIED", GREEN)); h.add(tag(po.type, offer ? GREEN : GOLD)); h.add(tag(po.category, BLUE));
        h.add(lbl(ago(po.time), Font.PLAIN, 11, MUTED)); put(inf, h);
        put(inf, lbl((offer ? "Teaching: " : "Wants to learn: ") + po.skill, Font.BOLD, 14, PINK));
        put(inf, lbl("<html><body style='width:380px'>" + esc(po.desc) + "</body></html>", Font.PLAIN, 12, MUTED));
        if (a != null && a.publicRatings && !reviews(a).isEmpty()) put(inf, lbl(stars(avg(a)) + " " + String.format("%.1f", avg(a)) + " (" + reviews(a).size() + " reviews)", Font.PLAIN, 11, GOLD));
        c.add(inf, BorderLayout.CENTER); JPanel b = row(); b.setPreferredSize(new Dimension(150, 70));
        if (mine) b.add(ghost("Delete", () -> { S.posts.remove(po); save(); show("Feed"); }));
        else {
            b.add(btn(offer ? "BOOK SESSION" : "OFFER TO TEACH", () -> { if (offer) book(po.author, me.username, po.skill); else book(me.username, po.author, po.skill); }));
            b.add(ghost("Message", () -> { chatWith = po.author; show("Messages"); })); b.add(ghost("View", () -> profileDialog(a))); b.add(ghost("\u2691", () -> report(po.author)));
        }
        c.add(b, BorderLayout.EAST); return c;
    }
    static void newPost() {
        JComboBox<String> type = combo(new String[]{"OFFER (I can teach)", "REQUEST (I want to learn)"});
        JComboBox<String> skill = combo(CAT.keySet().stream().map(s -> s.substring(0, 1).toUpperCase() + s.substring(1)).toArray(String[]::new)); skill.setEditable(true);
        JTextArea d = new JTextArea(4, 24); d.setLineWrap(true); d.setWrapStyleWord(true); d.setBackground(PANEL2); d.setForeground(TXT); d.setCaretColor(TXT);
        if (!ask("New post", form(new String[]{"Type", "Skill", "Details"}, new JComponent[]{type, skill, new JScrollPane(d)}))) return;
        String sk = String.valueOf(skill.getSelectedItem()).trim(); if (sk.isEmpty()) return;
        Post po = new Post(); po.id = S.nextId++; po.author = me.username; po.type = type.getSelectedIndex() == 0 ? "OFFER" : "REQUEST"; po.skill = sk; po.category = cat(sk);
        po.desc = d.getText().trim().isEmpty() ? "(no details)" : d.getText().trim(); po.time = System.currentTimeMillis(); S.posts.add(po);
        List<String> l = po.type.equals("OFFER") ? me.teach : me.learn; if (l.stream().noneMatch(x -> x.equalsIgnoreCase(sk))) l.add(sk);
        save(); feedFilter = "All"; show("Feed"); toast("Post published");
    }

    // ====================== SEARCH ======================
    static JComponent searchPage() {
        Pg p = pg("Search Topics", "Find a peer tutor by skill or category", null);
        JTextField q = field(); q.setText(searchQ); q.setFont(f(Font.PLAIN, 14)); q.addActionListener(e -> { searchQ = q.getText().trim(); show("Search"); });
        JPanel sr = new JPanel(new BorderLayout(6, 0)); sr.setOpaque(false); sr.add(q); sr.add(btn("Search", () -> { searchQ = q.getText().trim(); show("Search"); }), BorderLayout.EAST); put(p.body, sr); gap(p.body, 6);
        JPanel chips = row(); for (String c : CATS) chips.add(chip(c, c.equals(searchCat), () -> { searchCat = c; show("Search"); })); put(p.body, chips); gap(p.body, 8);
        if (!searchQ.isEmpty() || !searchCat.equals("All")) {
            put(p.body, lbl("Results", Font.BOLD, 13, MUTED)); gap(p.body, 4); int n = 0;
            for (User u : S.users.values()) { if (u == me) continue;
                for (String s : u.teach) if ((searchQ.isEmpty() || s.toLowerCase().contains(searchQ.toLowerCase())) && (searchCat.equals("All") || cat(s).equals(searchCat))) { put(p.body, tutorCard(u, s)); gap(p.body, 6); n++; } }
            if (n == 0) put(p.body, lbl("No tutors found. Try posting a request in the Skill Feed!", Font.PLAIN, 12, MUTED));
            gap(p.body, 10);
        }
        put(p.body, lbl("Trending topics", Font.BOLD, 13, MUTED)); gap(p.body, 4);
        Map<String, Integer> cnt = new TreeMap<>(); for (User u : S.users.values()) if (u != me) for (String s : u.teach) cnt.merge(s, 1, Integer::sum);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(cnt.entrySet()); es.sort((x, y) -> y.getValue() - x.getValue());
        JPanel g = new JPanel(new GridLayout(0, 2, 10, 10)); g.setOpaque(false);
        for (Map.Entry<String, Integer> e : es) {
            JPanel t = card(); t.add(lbl(e.getKey(), Font.BOLD, 13, TXT), BorderLayout.CENTER); t.add(lbl(e.getValue() + " tutors", Font.PLAIN, 11, MUTED), BorderLayout.SOUTH); t.add(tag(cat(e.getKey()).toUpperCase(), BLUE), BorderLayout.EAST);
            t.setCursor(new Cursor(Cursor.HAND_CURSOR)); t.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent ev) { searchQ = e.getKey(); show("Search"); } }); g.add(t);
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
        List<String[]> bs = badges(me); long earned = bs.stream().filter(b -> b[2].equals("Earned")).count();
        Pg p = pg("Verified Certificates", "Credentials earned by teaching and strong reviews", btn("+ Request", () -> {
            if (verified(me)) info("You already hold the Top Rated Tutor verification badge. Great work!");
            else info("Verification is automatic once you have 3+ reviews with a 4.5+ average.\nYou currently have " + reviews(me).size() + " review(s), average " + String.format("%.1f", avg(me)) + ".\nComplete more sessions to qualify."); }));
        JPanel st = row(); st.add(tag("EARNED " + earned, GREEN)); st.add(tag("IN PROGRESS " + bs.stream().filter(b -> b[2].equals("In progress")).count(), GOLD)); st.add(tag("LOCKED " + bs.stream().filter(b -> b[2].equals("Locked")).count(), MUTED)); put(p.body, st); gap(p.body, 6);
        for (String[] b : bs) {
            JPanel c = card(); JPanel inf = vbox(); JPanel h = row(); h.add(lbl(b[0], Font.BOLD, 14, TXT)); h.add(tag(b[2].toUpperCase(), b[2].equals("Earned") ? GREEN : b[2].equals("In progress") ? GOLD : MUTED)); put(inf, h);
            put(inf, lbl(b[1] + "   \u2022   Progress: " + b[3], Font.PLAIN, 12, MUTED)); c.add(inf);
            if (b[2].equals("Earned")) { JPanel r = row(); r.add(ghost("View", () -> info("CERTIFICATE OF ACHIEVEMENT\n\n" + name(me) + "\nhas earned: " + b[0] + "\n" + b[1] + "\n\nIssued " + LocalDate.now() + " by SkillSwap Campus")));
                r.add(ghost("Share", () -> { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(name(me) + " earned " + b[0] + " on SkillSwap Campus"), null); toast("Copied to clipboard"); })); c.add(r, BorderLayout.EAST); }
            put(p.body, c); gap(p.body, 8);
        }
        return p.root;
    }

    // ====================== BOOKINGS ======================
    static LocalDateTime pickTime(String title, JComponent... extra) { return null; }
    static void book(String teacher, String learner, String skill) {
        if (learner.equals(me.username) && me.credits < 1) { info("You need at least 1 swap credit to request a session.\nTeach a session to earn credits!"); return; }
        JComboBox<String> day = combo(new String[7]), time = combo(new String[10]), mode = combo(new String[]{"Online", "In person"}); List<LocalDate> days = new ArrayList<>();
        DefaultComboBoxModel<String> dm = new DefaultComboBoxModel<>(); for (int i = 0; i < 7; i++) { LocalDate d = LocalDate.now().plusDays(i); days.add(d); dm.addElement(d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))); } day.setModel(dm);
        DefaultComboBoxModel<String> tm = new DefaultComboBoxModel<>(); for (int h = 9; h < 19; h++) tm.addElement(LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h:mm a"))); time.setModel(tm);
        if (!ask("Book: " + skill + " (" + name(teacher) + " teaching, " + name(learner) + " learning)", form(new String[]{"Day", "Time", "Mode"}, new JComponent[]{day, time, mode}))) return;
        Sess s = new Sess(); s.id = S.nextId++; s.teacher = teacher; s.learner = learner; s.skill = skill; s.mode = String.valueOf(mode.getSelectedItem()); s.status = "PENDING"; s.requester = me.username;
        s.at = LocalDateTime.of(days.get(day.getSelectedIndex()), LocalTime.of(9 + time.getSelectedIndex(), 0)); S.sessions.add(s); save();
        String o = teacher.equals(me.username) ? learner : teacher; toast("Request sent to " + name(o));
        if (S.users.get(o).demo) { Timer t = new Timer(2500, e -> { if (s.status.equals("PENDING")) { if (confirm(s)) notify("Swap accepted", name(o) + " accepted your " + s.skill + " session"); else { s.status = "DECLINED"; save(); } if (me != null) show(page); } }); t.setRepeats(false); t.start(); }
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
        for (int i = 0; i < 7; i++) { LocalDate d = td.plusDays(i); long n = S.sessions.stream().filter(s -> s.status.equals("CONFIRMED") && involves(s, me) && s.at.toLocalDate().equals(d)).count();
            JButton b = chip(d.format(DateTimeFormatter.ofPattern("EEE d")) + (n > 0 ? " (" + n + ")" : ""), i == 0, () -> toast(n + " confirmed session(s) on " + d)); days.add(b); }
        put(p.body, days);
        List<Sess> mine = S.sessions.stream().filter(s -> involves(s, me)).sorted(Comparator.comparing((Sess s) -> s.at)).collect(Collectors.toList());
        List<Sess> inc = mine.stream().filter(s -> s.status.equals("PENDING") && !s.requester.equals(me.username)).collect(Collectors.toList());
        JPanel chips = row(); for (String t : new String[]{"Upcoming", "Past", "Requests"}) chips.add(chip(t + (t.equals("Requests") && !inc.isEmpty() ? " (" + inc.size() + ")" : ""), t.equals(bookTab), () -> { bookTab = t; show("Bookings"); })); put(p.body, chips); gap(p.body, 6);
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
                b.add(btn("Join", () -> info("Session room: SS-" + (1000 + s.id) + "\n" + (s.mode.equals("Online") ? "Open the campus LAN meeting room with this code." : "Meet in person at the agreed campus spot."))));
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
        s.at = LocalDateTime.of(days.get(day.getSelectedIndex()), LocalTime.of(9 + time.getSelectedIndex(), 0)); s.reminded = false; save(); toast("Session rescheduled"); show("Bookings");
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
        Msg m = new Msg(); m.from = me.username; m.to = to; m.text = text; m.time = System.currentTimeMillis(); S.msgs.add(m); save();
        User o = S.users.get(to);
        if (o != null && o.demo) { String[] rp = {"Sounds good!", "Sure - when are you free?", "Happy to swap skills with you!", "Let me check my schedule and get back to you.", "Great, send me a booking request."};
            Timer t = new Timer(1500, e -> { Msg r = new Msg(); r.from = to; r.to = m.from; r.text = rp[new Random().nextInt(rp.length)]; r.time = System.currentTimeMillis(); S.msgs.add(r); save();
                if (me != null && page.equals("Messages") && to.equals(chatWith)) show("Messages"); else if (me != null) notify("New message", name(to) + ": " + r.text); }); t.setRepeats(false); t.start(); }
    }
    static JComponent messagesPage() {
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(BG);
        JPanel left = vbox(); left.setBorder(new EmptyBorder(14, 12, 10, 12)); put(left, lbl("Messages", Font.BOLD, 20, TXT)); gap(left, 6);
        JTextField q = field(); q.setText(chatQ); q.addActionListener(e -> { chatQ = q.getText().trim(); show("Messages"); }); put(left, q); gap(left, 6);
        for (User u : S.users.values()) { if (u == me || (!chatQ.isEmpty() && !name(u).toLowerCase().contains(chatQ.toLowerCase()))) continue;
            Msg last = null; for (Msg m : S.msgs) if ((m.from.equals(me.username) && m.to.equals(u.username)) || (m.to.equals(me.username) && m.from.equals(u.username))) last = m;
            JButton b = new JButton("<html><b>" + esc(name(u)) + "</b><br><span style='font-size:9px'>" + esc(last == null ? "No messages yet" : last.text.length() > 24 ? last.text.substring(0, 24) + "..." : last.text) + "</span></html>");
            b.setHorizontalAlignment(SwingConstants.LEFT); b.setForeground(TXT); b.setBackground(u.username.equals(chatWith) ? PANEL2 : PANEL); b.setOpaque(true); b.setBorderPainted(false); b.setFocusPainted(false);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46)); String un = u.username; b.addActionListener(e -> { chatWith = un; show("Messages"); }); put(left, b); gap(left, 3); }
        JScrollPane ls = new JScrollPane(left); ls.setBorder(null); ls.setPreferredSize(new Dimension(230, 0)); ls.getViewport().setBackground(BG); root.add(ls, BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout()); right.setBackground(BG);
        if (chatWith == null || !S.users.containsKey(chatWith)) { JPanel e = new JPanel(new GridBagLayout()); e.setOpaque(false); e.add(lbl("Select a conversation to start chatting", Font.PLAIN, 13, MUTED)); right.add(e); }
        else {
            User o = S.users.get(chatWith); JPanel h = new JPanel(new BorderLayout()); h.setBackground(PANEL); h.setBorder(new EmptyBorder(8, 12, 8, 12)); JPanel hi = vbox();
            put(hi, lbl(name(o), Font.BOLD, 14, TXT)); put(hi, lbl(o.nearby ? "\u25CF Online - open to swap" : "Offline", Font.PLAIN, 11, o.nearby ? GREEN : MUTED)); h.add(hi);
            JPanel hb = row(); if (!o.teach.isEmpty()) hb.add(btn("Book Session", () -> book(o.username, me.username, o.teach.get(0)))); hb.add(ghost("\u2691 Report", () -> report(o.username))); h.add(hb, BorderLayout.EAST); right.add(h, BorderLayout.NORTH);
            JPanel list = vbox(); list.setBorder(new EmptyBorder(10, 14, 10, 14));
            for (Msg m : S.msgs) { boolean mineM = m.from.equals(me.username); if (!((mineM && m.to.equals(o.username)) || (m.from.equals(o.username) && m.to.equals(me.username)))) continue;
                JLabel bub = lbl("<html><body style='width:260px'>" + esc(m.text) + "</body></html>", Font.PLAIN, 12, TXT); bub.setOpaque(true); bub.setBackground(mineM ? PINK : PANEL2); bub.setBorder(new EmptyBorder(8, 12, 8, 12));
                JPanel r = new JPanel(new FlowLayout(mineM ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 3)); r.setOpaque(false); r.add(bub); put(list, r); }
            JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false); wrap.add(list, BorderLayout.NORTH); JScrollPane sp = new JScrollPane(wrap); sp.setBorder(null); sp.getViewport().setBackground(BG);
            SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum())); right.add(sp);
            JTextField in = field(); Runnable send = () -> { String t = in.getText().trim(); if (!t.isEmpty()) { sendMsg(o.username, t); show("Messages"); } }; in.addActionListener(e -> send.run());
            JPanel bar = new JPanel(new BorderLayout(6, 0)); bar.setBackground(PANEL); bar.setBorder(new EmptyBorder(8, 12, 8, 12)); bar.add(in); bar.add(btn("Send", send), BorderLayout.EAST); right.add(bar, BorderLayout.SOUTH);
        }
        root.add(right); return root;
    }

    // ====================== PROFILE ======================
    static JComponent profilePage() {
        Pg p = pg("My Profile & Rating", me.email, btn("Edit Profile", SkillSwapCampus::editProfile));
        JPanel hd = card(); hd.add(avatarWrap(name(me), 64), BorderLayout.WEST); JPanel inf = vbox(); JPanel nm = row(); nm.add(lbl(name(me), Font.BOLD, 18, TXT)); if (verified(me)) nm.add(tag("VERIFIED TUTOR", GREEN));
        nm.add(tag(me.publicRatings ? "RATINGS PUBLIC" : "RATINGS PRIVATE", MUTED)); put(inf, nm); put(inf, lbl(me.useRealName ? "Showing real name (username: " + me.username + ")" : "Showing username" + (me.realName.isEmpty() ? "" : " (real name hidden)"), Font.PLAIN, 11, MUTED));
        put(inf, lbl(me.bio.isEmpty() ? "No bio yet - use Edit Profile" : me.bio, Font.PLAIN, 12, MUTED)); hd.add(inf); put(p.body, hd); gap(p.body, 8);
        JPanel stats = new JPanel(new GridLayout(1, 4, 8, 0)); stats.setOpaque(false);
        long sess = S.sessions.stream().filter(s -> s.status.equals("COMPLETED") && involves(s, me)).count();
        String[][] sv = {{"" + sess, "Sessions"}, {"" + reviews(me).size(), "Reviews"}, {reviews(me).isEmpty() ? "-" : String.format("%.1f", avg(me)), "Avg rating"}, {"" + me.credits, "Credits"}};
        for (String[] s : sv) { JPanel c = card(); c.add(lbl(s[0], Font.BOLD, 22, TXT), BorderLayout.CENTER); c.add(lbl(s[1], Font.PLAIN, 11, MUTED), BorderLayout.SOUTH); stats.add(c); } put(p.body, stats); gap(p.body, 10);
        put(p.body, skillRow("I can teach", me.teach, GREEN)); gap(p.body, 6); put(p.body, skillRow("I want to learn", me.learn, GOLD)); gap(p.body, 10);
        put(p.body, lbl("My rating", Font.BOLD, 13, MUTED)); gap(p.body, 4); JPanel rc = card(); JPanel rv = vbox(); List<Sess> rs = reviews(me);
        for (int st = 5; st >= 1; st--) { final int s2 = st; long n = rs.stream().filter(x -> x.rating == s2).count(); JPanel r = new JPanel(new BorderLayout(8, 0)); r.setOpaque(false);
            r.add(lbl(st + " \u2605", Font.PLAIN, 11, GOLD), BorderLayout.WEST); JProgressBar pb = new JProgressBar(0, Math.max(1, rs.size())); pb.setValue((int) n); pb.setForeground(PINK); pb.setBackground(PANEL2); pb.setBorderPainted(false); r.add(pb); r.add(lbl("" + n, Font.PLAIN, 11, MUTED), BorderLayout.EAST); put(rv, r); gap(rv, 3); }
        rc.add(rv); put(p.body, rc); gap(p.body, 10);
        JPanel acts = row(); acts.add(btn("Share Profile", () -> { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection("SkillSwap Campus - " + name(me) + " | Teaches: " + String.join(", ", me.teach) + " | Wants: " + String.join(", ", me.learn)
                + (me.publicRatings && !rs.isEmpty() ? " | Rating " + String.format("%.1f", avg(me)) : "")), null); toast("Profile summary copied to clipboard"); }));
        acts.add(ghost("Sync ratings online", SkillSwapCampus::sync)); JCheckBox nb = check("Open to impromptu swap now", me.nearby); nb.addActionListener(e -> { me.nearby = nb.isSelected(); save(); }); acts.add(nb); put(p.body, acts); gap(p.body, 10);
        put(p.body, lbl("Recently reviewed", Font.BOLD, 13, MUTED)); gap(p.body, 4);
        if (rs.isEmpty()) put(p.body, lbl("No reviews yet - complete a session to receive feedback.", Font.PLAIN, 12, MUTED));
        for (int i = rs.size() - 1; i >= 0; i--) { Sess s = rs.get(i); JPanel c = card(); JPanel iv = vbox(); put(iv, lbl(name(s.learner) + "   " + stars(s.rating), Font.BOLD, 12, GOLD)); put(iv, lbl(s.skill + (s.review.isEmpty() ? "" : " - " + s.review), Font.PLAIN, 12, MUTED)); c.add(iv); put(p.body, c); gap(p.body, 5); }
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
        Report rp = new Report(); rp.by = me.username; rp.target = target; rp.reason = String.valueOf(r.getSelectedItem()); rp.details = d.getText().trim(); rp.time = System.currentTimeMillis(); S.reports.add(rp); save();
        toast("Report submitted to campus moderators");
    }
    static void sync() {
        try { Files.createDirectories(DIR); StringBuilder j = new StringBuilder("{\"synced\":\"" + LocalDateTime.now() + "\",\"profiles\":[");
            boolean first = true; for (User u : S.users.values()) if (u.publicRatings && !u.demo) { if (!first) j.append(","); first = false;
                j.append("{\"name\":\"").append(name(u).replace("\"", "'")).append("\",\"rating\":").append(String.format("%.2f", avg(u))).append(",\"reviews\":").append(reviews(u).size()).append("}"); }
            j.append("]}"); Files.writeString(DIR.resolve("cloud-sync.json"), j.toString()); S.lastSync = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")); save();
            toast("Ratings & profile synced (simulated cloud: " + DIR.resolve("cloud-sync.json") + ")"); show(page);
        } catch (Exception e) { info("Offline - will sync later. Core swaps keep working on the local network."); }
    }
}