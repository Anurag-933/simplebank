/*
Complete Single-file: SimpleBankAppRunner.java
- Matches your original layout and behavior (Option D)
- Fixes the truncated / missing parts and improves robustness:
  * safe casting of table values
  * proper transaction handling (commit/rollback + autoCommit restore)
  * prevents approving non-pending tx
  * accountant can search by account/username/full name and approve/reject
- NOTE: Passwords stored in plain text here (same as your original). For production, hash them.
- Make sure MySQL is running and the JDBC URL / credentials below are correct.
*/

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;

public class SimpleBankAppRunner extends JFrame {
    // ========== DB CONFIG ==========
    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/hdfc_bank?serverTimezone=UTC&useSSL=false";
    private static final String JDBC_USER = "root";     // <<-- change if needed
    private static final String JDBC_PASS = "Anurag@9336*";         // <<-- change to your MySQL password

    private Connection conn;

    // UI and state
    private CardLayout cards = new CardLayout();
    private JPanel root = new JPanel(cards);

    private JTextField loginUser = new JTextField(20);
    private JPasswordField loginPass = new JPasswordField(20);

    private JTextField signFull = new JTextField(20);
    private JTextField signUser = new JTextField(20);
    private JPasswordField signPass = new JPasswordField(20);

    private int currentUserId = -1;
    private int currentAccountId = -1;
    private String currentAccountNumber = null;

    private JLabel lblCustomerWelcome = new JLabel("");
    private JLabel lblBalance = new JLabel("Balance: -");
    private DefaultTableModel custHistoryModel;
    private JTable tblCustHistory;

    private JTextField accSearchField = new JTextField(20);
    private JLabel accCustomerInfo = new JLabel("No customer selected");
    private DefaultTableModel pendingModel;
    private JTable tblPending;
    private DefaultTableModel accHistoryModel;
    private JTable tblAccHistory;

    // Colors
    private final Color BG = new Color(34, 40, 49);
    private final Color BTN = new Color(0, 123, 255);
    private final Color FG = Color.WHITE;

    public SimpleBankAppRunner() {
        setTitle("SimpleBank - Fixed");
        setSize(920, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        // 1) Load driver
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            JOptionPane.showMessageDialog(null, "MySQL JDBC Driver not found. Add mysql-connector-java to classpath.\n" + e.getMessage());
            System.exit(1);
        }

        // 2) Connect & ensure schema
        try {
            conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
            ensureSchemaAndSeedAdmin();
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(null, "DB connection/initialization failed:\n" + ex.getMessage());
            System.exit(1);
        }

        // 3) Build UI (panels)
        root.add(buildLoginPanel(), "login");
        root.add(buildSignupPanel(), "signup");
        root.add(buildCustomerPanel(), "customer");
        root.add(buildAccountantLoginPanel(), "accLogin");
        root.add(buildAccountantPanel(), "accountant");
        add(root);
        cards.show(root, "login");
    }

    // ========= DB: ensure tables + seed admin =========
    private void ensureSchemaAndSeedAdmin() throws SQLException {
        String createUsers =
                "CREATE TABLE IF NOT EXISTS users (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY," +
                        "full_name VARCHAR(200) NOT NULL," +
                        "username VARCHAR(100) UNIQUE NOT NULL," +
                        "password VARCHAR(255) NOT NULL" +
                        ");";
        String createAccounts =
                "CREATE TABLE IF NOT EXISTS accounts (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY," +
                        "user_id INT NOT NULL," +
                        "account_number VARCHAR(50) UNIQUE NOT NULL," +
                        "balance DOUBLE DEFAULT 0," +
                        "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE" +
                        ");";
        String createTransactions =
                "CREATE TABLE IF NOT EXISTS transactions (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY," +
                        "account_id INT NOT NULL," +
                        "type ENUM('DEPOSIT','WITHDRAW') NOT NULL," +
                        "amount DOUBLE NOT NULL," +
                        "status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING'," +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "approved_by INT NULL," +
                        "approved_at TIMESTAMP NULL," +
                        "FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE" +
                        ");";

        try (Statement st = conn.createStatement()) {
            st.execute(createUsers);
            st.execute(createAccounts);
            st.execute(createTransactions);
        }

        // seed admin if not exists
        String check = "SELECT id FROM users WHERE username='admin' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(check);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                String insert = "INSERT INTO users(full_name, username, password) VALUES(?, 'admin', '1234')";
                try (PreparedStatement ps2 = conn.prepareStatement(insert)) {
                    ps2.setString(1, "System Administrator");
                    ps2.executeUpdate();
                }
            }
        }
    }

    // ========= UI builders =========
    private JPanel basePanel() {
        JPanel p = new JPanel(null);
        p.setBackground(BG);
        return p;
    }

    private JButton btn(String text) {
        JButton b = new JButton(text);
        b.setBackground(BTN);
        b.setForeground(FG);
        b.setFocusPainted(false);
        return b;
    }

    private JLabel lab(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(FG);
        return l;
    }

    private JPanel buildLoginPanel() {
        JPanel p = basePanel();
        JLabel title = new JLabel("Login");
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        title.setForeground(FG);
        title.setBounds(380, 20, 200, 40);
        p.add(title);

        JLabel luser = lab("Username:");
        luser.setBounds(260, 90, 100, 25);
        p.add(luser);
        loginUser.setBounds(360, 90, 240, 28);
        p.add(loginUser);

        JLabel lpass = lab("Password:");
        lpass.setBounds(260, 130, 100, 25);
        p.add(lpass);
        loginPass.setBounds(360, 130, 240, 28);
        p.add(loginPass);

        JButton loginBtn = btn("Login");
        loginBtn.setBounds(360, 180, 240, 34);
        loginBtn.addActionListener(e -> doLogin());
        p.add(loginBtn);

        JButton toSign = btn("Create Customer Account");
        toSign.setBounds(360, 230, 240, 34);
        toSign.addActionListener(e -> cards.show(root, "signup"));
        p.add(toSign);

        JButton accLogin = btn("Accountant Login");
        accLogin.setBounds(360, 280, 240, 34);
        accLogin.addActionListener(e -> cards.show(root, "accLogin"));
        p.add(accLogin);

        return p;
    }

    private JPanel buildSignupPanel() {
        JPanel p = basePanel();
        JLabel title = new JLabel("Create Customer Account");
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(FG);
        title.setBounds(320, 20, 400, 30);
        p.add(title);

        JLabel fn = lab("Full Name:"); fn.setBounds(240, 90, 120, 25); p.add(fn);
        signFull.setBounds(360, 90, 300, 28); p.add(signFull);

        JLabel un = lab("Username:"); un.setBounds(240, 130, 120, 25); p.add(un);
        signUser.setBounds(360, 130, 300, 28); p.add(signUser);

        JLabel pw = lab("Password:"); pw.setBounds(240, 170, 120, 25); p.add(pw);
        signPass.setBounds(360, 170, 300, 28); p.add(signPass);

        JButton create = btn("Create Account"); create.setBounds(360, 220, 300, 36);
        create.addActionListener(e -> doSignup()); p.add(create);

        JButton back = btn("Back"); back.setBounds(20, 20, 100, 30); back.addActionListener(e -> cards.show(root, "login")); p.add(back);
        return p;
    }

    private JPanel buildCustomerPanel() {
        JPanel p = basePanel();
        JLabel title = new JLabel("Customer Dashboard");
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(FG); title.setBounds(30, 10, 400, 30); p.add(title);

        lblCustomerWelcome.setBounds(30,45,600,24); lblCustomerWelcome.setForeground(FG); p.add(lblCustomerWelcome);
        lab("Available Balance:").setBounds(30,80,150,25); p.add(lab("Available Balance:"));
        lblBalance.setBounds(180,80,200,25); lblBalance.setForeground(FG); p.add(lblBalance);

        JButton deposit = btn("Deposit (PENDING)"); deposit.setBounds(30,120,220,36); deposit.addActionListener(e->doCreateTransaction("DEPOSIT")); p.add(deposit);
        JButton withdraw = btn("Withdraw (PENDING)"); withdraw.setBounds(260,120,220,36); withdraw.addActionListener(e->doCreateTransaction("WITHDRAW")); p.add(withdraw);
        JButton refresh = btn("Refresh History"); refresh.setBounds(490,120,180,36); refresh.addActionListener(e->loadCustomerHistory()); p.add(refresh);
        JButton checkBal = btn("Check Balance"); checkBal.setBounds(690,120,180,36); checkBal.addActionListener(e->showBalance()); p.add(checkBal);

        custHistoryModel = new DefaultTableModel(new String[]{"ID","Type","Amount","Status","Created"},0){
            public boolean isCellEditable(int r,int c){return false;}
        };
        tblCustHistory = new JTable(custHistoryModel);
        JScrollPane sp = new JScrollPane(tblCustHistory);
        sp.setBounds(30,180,840,310); p.add(sp);

        JButton logout = btn("Logout"); logout.setBounds(780,500,120,36);
        logout.addActionListener(e->{
            currentUserId=-1; currentAccountId=-1; currentAccountNumber=null;
            cards.show(root,"login");
        });
        p.add(logout);
        return p;
    }

    private JPanel buildAccountantLoginPanel() {
        JPanel p = basePanel();
        JLabel title = new JLabel("Accountant Login (admin / 1234)");
        title.setFont(new Font("SansSerif", Font.BOLD, 20)); title.setForeground(FG); title.setBounds(300,30,400,30); p.add(title);

        JTextField aUser = new JTextField(); JPasswordField aPass = new JPasswordField();
        lab("Username:").setBounds(280,100,120,25); p.add(lab("Username:"));
        aUser.setBounds(380,100,220,28); p.add(aUser);
        lab("Password:").setBounds(280,140,120,25); p.add(lab("Password:"));
        aPass.setBounds(380,140,220,28); p.add(aPass);

        JButton login = btn("Login"); login.setBounds(380,190,220,34);
        login.addActionListener(e->{
            if (aUser.getText().trim().equals("admin") && new String(aPass.getPassword()).trim().equals("1234")) {
                loadPendingTransactions();
                cards.show(root,"accountant");
            } else {
                JOptionPane.showMessageDialog(this,"Wrong accountant credentials");
            }
        });
        p.add(login);

        JButton back = btn("Back"); back.setBounds(20,20,100,30); back.addActionListener(e->cards.show(root,"login")); p.add(back);
        return p;
    }

    private JPanel buildAccountantPanel() {
        JPanel p = basePanel();
        JLabel title = new JLabel("Accountant Dashboard");
        title.setFont(new Font("SansSerif", Font.BOLD, 22)); title.setForeground(FG); title.setBounds(30,10,400,30); p.add(title);

        accSearchField.setBounds(30,60,320,28); p.add(accSearchField);
        JButton search = btn("Find Customer"); search.setBounds(360,60,160,30); search.addActionListener(e->findCustomerForAccountant(accSearchField.getText().trim())); p.add(search);
        JButton refresh = btn("Refresh Pending"); refresh.setBounds(540,60,160,30); refresh.addActionListener(e->loadPendingTransactions()); p.add(refresh);

        accCustomerInfo.setForeground(FG); accCustomerInfo.setBounds(30,100,600,25); p.add(accCustomerInfo);

        pendingModel = new DefaultTableModel(new String[]{"ID","Account#","Type","Amount","Created"},0){
            public boolean isCellEditable(int r,int c){return false;}
        };
        tblPending = new JTable(pendingModel);
        JScrollPane psp = new JScrollPane(tblPending); psp.setBounds(30,140,420,240); p.add(psp);

        // ====== FIX: enable selection + disable approve/reject until selection is made ======
        tblPending.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblPending.setRowSelectionAllowed(true);

        JButton approve = btn("Approve");
        JButton reject = btn("Reject");
        approve.setEnabled(false);
        reject.setEnabled(false);

        // when a row is selected, enable approve/reject
        tblPending.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    int row = tblPending.getSelectedRow();
                    boolean sel = (row != -1);
                    approve.setEnabled(sel);
                    reject.setEnabled(sel);
                }
            }
        });

        // place buttons (keep original bounds)
        approve.setBounds(470,160,140,36);
        approve.addActionListener(e->approveSelected(true));
        p.add(approve);

        reject.setBounds(470,210,140,36);
        reject.addActionListener(e->approveSelected(false));
        p.add(reject);
        // ====== END FIX ======

        accHistoryModel = new DefaultTableModel(new String[]{"ID","Account#","Type","Amount","Status","Created"},0){
            public boolean isCellEditable(int r,int c){return false;}
        };
        tblAccHistory = new JTable(accHistoryModel);
        JScrollPane hsp = new JScrollPane(tblAccHistory); hsp.setBounds(30,400,840,140); p.add(hsp);

        JButton logout = btn("Logout"); logout.setBounds(760,20,120,36); logout.addActionListener(e->cards.show(root,"login")); p.add(logout);

        return p;
    }

    // ========= Actions =========
    private void doSignup() {
        String full = signFull.getText().trim();
        String username = signUser.getText().trim();
        String password = new String(signPass.getPassword()).trim();
        if (full.isEmpty()||username.isEmpty()||password.isEmpty()) { JOptionPane.showMessageDialog(this,"Fill all fields"); return; }
        try {
            String sql = "INSERT INTO users(full_name, username, password) VALUES (?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, full); ps.setString(2, username); ps.setString(3, password); ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int uid = rs.getInt(1);
                        String accNo = "AC" + System.currentTimeMillis();
                        String aSql = "INSERT INTO accounts(user_id, account_number, balance) VALUES (?,?,0)";
                        try (PreparedStatement ps2 = conn.prepareStatement(aSql)) {
                            ps2.setInt(1, uid); ps2.setString(2, accNo); ps2.executeUpdate();
                        }
                        JOptionPane.showMessageDialog(this,"Account created! Account number: "+accNo);
                        signFull.setText(""); signUser.setText(""); signPass.setText("");
                        cards.show(root,"login");
                    } else JOptionPane.showMessageDialog(this,"Signup failed");
                }
            }
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1062) JOptionPane.showMessageDialog(this,"Username already exists");
            else JOptionPane.showMessageDialog(this,"Signup error: "+ex.getMessage());
        }
    }

    private void doLogin() {
        String username = loginUser.getText().trim();
        String password = new String(loginPass.getPassword()).trim();
        if (username.isEmpty()||password.isEmpty()) { JOptionPane.showMessageDialog(this,"Enter credentials"); return; }
        try {
            String sql = "SELECT id FROM users WHERE username=? AND password=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username); ps.setString(2, password);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentUserId = rs.getInt(1);
                        String aSql = "SELECT id, account_number FROM accounts WHERE user_id=?";
                        try (PreparedStatement ps2 = conn.prepareStatement(aSql)) {
                            ps2.setInt(1, currentUserId);
                            try (ResultSet rs2 = ps2.executeQuery()) {
                                if (rs2.next()) {
                                    currentAccountId = rs2.getInt("id");
                                    currentAccountNumber = rs2.getString("account_number");
                                } else { JOptionPane.showMessageDialog(this,"No account found for user"); return; }
                            }
                        }
                        lblCustomerWelcome.setText("Welcome, " + username + " (Account: " + currentAccountNumber + ")");
                        loadCustomerHistory();
                        cards.show(root,"customer");
                        loginUser.setText(""); loginPass.setText("");
                    } else JOptionPane.showMessageDialog(this,"Invalid credentials");
                }
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this,"Login error: "+ex.getMessage());
        }
    }

    private void doCreateTransaction(String type) {
        if (currentAccountId==-1) { JOptionPane.showMessageDialog(this,"Not logged in properly"); return; }
        String s = JOptionPane.showInputDialog(this,"Enter amount:");
        if (s==null) return;
        double amt;
        try { amt = Double.parseDouble(s); if (amt<=0) { JOptionPane.showMessageDialog(this,"Enter positive amount"); return; } }
        catch (NumberFormatException nfe) { JOptionPane.showMessageDialog(this,"Invalid amount"); return; }
        try {
            String sql = "INSERT INTO transactions(account_id, type, amount, status) VALUES(?,?,?, 'PENDING')";
            try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.setInt(1, currentAccountId); ps.setString(2, type); ps.setDouble(3, amt); ps.executeUpdate(); }
            JOptionPane.showMessageDialog(this,"Transaction created and is PENDING approval by accountant.");
            loadCustomerHistory();
        } catch (SQLException ex) { JOptionPane.showMessageDialog(this,"Error creating transaction: "+ex.getMessage()); }
    }

    private void showBalance() {
        if (currentAccountId==-1) return;
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM accounts WHERE id=?")) {
            ps.setInt(1, currentAccountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) lblBalance.setText(String.format("Balance: %.2f", rs.getDouble("balance")));
                else lblBalance.setText("Balance: -");
            }
        } catch (SQLException ex) { JOptionPane.showMessageDialog(this,"Error reading balance: "+ex.getMessage()); }
    }

    private void loadCustomerHistory() {
        custHistoryModel.setRowCount(0);
        if (currentAccountId==-1) return;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id,type,amount,status,created_at FROM transactions WHERE account_id=? ORDER BY created_at DESC")) {
            ps.setInt(1, currentAccountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) custHistoryModel.addRow(new Object[]{rs.getInt(1), rs.getString(2), rs.getDouble(3), rs.getString(4), rs.getTimestamp(5)});
            }
            showBalance();
        } catch (SQLException ex) { JOptionPane.showMessageDialog(this,"Error loading history: "+ex.getMessage()); }
    }

    private void loadPendingTransactions() {
        pendingModel.setRowCount(0); accHistoryModel.setRowCount(0);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT t.id, a.account_number, t.type, t.amount, t.created_at FROM transactions t JOIN accounts a ON t.account_id=a.id WHERE t.status='PENDING' ORDER BY t.created_at ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pendingModel.addRow(new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getTimestamp(5)});
        } catch (SQLException ex) { JOptionPane.showMessageDialog(this,"Error loading pending: "+ex.getMessage()); }

        try (PreparedStatement ps2 = conn.prepareStatement(
                "SELECT t.id, a.account_number, t.type, t.amount, t.status, t.created_at FROM transactions t JOIN accounts a ON t.account_id=a.id ORDER BY t.created_at DESC LIMIT 200");
             ResultSet rs2 = ps2.executeQuery()) {
            while (rs2.next()) accHistoryModel.addRow(new Object[]{rs2.getInt(1), rs2.getString(2), rs2.getString(3), rs2.getDouble(4), rs2.getString(5), rs2.getTimestamp(6)});
        } catch (SQLException ex) { JOptionPane.showMessageDialog(this,"Error loading history: "+ex.getMessage()); }
    }

    private void findCustomerForAccountant(String q) {
        if (q.isEmpty()) { JOptionPane.showMessageDialog(this,"Enter account number or username/full name"); return; }
        pendingModel.setRowCount(0); accHistoryModel.setRowCount(0);
        String sql = "SELECT u.full_name, a.account_number, a.balance, a.id FROM users u JOIN accounts a ON u.id=a.user_id WHERE a.account_number=? OR u.username=? OR u.full_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, q); ps.setString(2, q); ps.setString(3, q);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String fname = rs.getString(1); String acct = rs.getString(2); double balance = rs.getDouble(3); int accId = rs.getInt(4);
                    accCustomerInfo.setText("Name: " + fname + " | Account#: " + acct + " | Balance: " + String.format("%.2f", balance));
                    // load pending for that account
                    try (PreparedStatement ps2 = conn.prepareStatement("SELECT id, type, amount, created_at FROM transactions WHERE account_id=? AND status='PENDING' ORDER BY created_at ASC")) {
                        ps2.setInt(1, accId);
                        try (ResultSet rs2 = ps2.executeQuery()) {
                            while (rs2.next()) pendingModel.addRow(new Object[]{rs2.getInt(1), acct, rs2.getString(2), rs2.getDouble(3), rs2.getTimestamp(4)});
                        }
                    }
                    try (PreparedStatement ph = conn.prepareStatement("SELECT id, (SELECT account_number FROM accounts WHERE id=transactions.account_id) as account_number, type, amount, status, created_at FROM transactions WHERE account_id=? ORDER BY created_at DESC")) {
                        ph.setInt(1, accId);
                        try (ResultSet rh = ph.executeQuery()) {
                            while (rh.next()) accHistoryModel.addRow(new Object[]{rh.getInt(1), rh.getString(2), rh.getString(3), rh.getDouble(4), rh.getString(5), rh.getTimestamp(6)});
                        }
                    }
                } else JOptionPane.showMessageDialog(this,"Customer not found");
            }
        } catch (SQLException ex) { JOptionPane.showMessageDialog(this,"Search error: "+ex.getMessage()); }
    }

    private void approveSelected(boolean approve) {
        int sel = tblPending.getSelectedRow();
        if (sel==-1) { JOptionPane.showMessageDialog(this,"Select a pending transaction first"); return; }
        // safe extraction of txId (handle Integer/Long)
        Object oid = pendingModel.getValueAt(sel, 0);
        int txId;
        if (oid instanceof Number) txId = ((Number) oid).intValue();
        else {
            try { txId = Integer.parseInt(oid.toString()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this,"Invalid transaction id"); return; }
        }

        try {
            String q = "SELECT account_id, type, amount, status FROM transactions WHERE id=?";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                ps.setInt(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { JOptionPane.showMessageDialog(this,"Transaction not found"); return; }
                    int accountId = rs.getInt("account_id"); String type = rs.getString("type"); double amt = rs.getDouble("amount"); String status = rs.getString("status");
                    if (!"PENDING".equals(status)) { JOptionPane.showMessageDialog(this,"Transaction is not pending"); return; }

                    if (!approve) {
                        try (PreparedStatement rps = conn.prepareStatement("UPDATE transactions SET status='REJECTED', approved_by=NULL, approved_at=NOW() WHERE id=?")) {
                            rps.setInt(1, txId); rps.executeUpdate();
                        }
                        JOptionPane.showMessageDialog(this,"Rejected transaction " + txId);
                        loadPendingTransactions();
                        return;
                    }

                    // APPROVE: update account balance (WITHDRAW needs balance check) atomically
                    boolean originalAuto = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        if ("WITHDRAW".equals(type)) {
                            try (PreparedStatement bps = conn.prepareStatement("SELECT balance FROM accounts WHERE id=? FOR UPDATE")) {
                                bps.setInt(1, accountId);
                                try (ResultSet brs = bps.executeQuery()) {
                                    if (!brs.next()) throw new SQLException("Account missing");
                                    double bal = brs.getDouble(1);
                                    if (bal < amt) {
                                        // insufficient -> reject
                                        try (PreparedStatement rps = conn.prepareStatement("UPDATE transactions SET status='REJECTED', approved_by=NULL, approved_at=NOW() WHERE id=?")) {
                                            rps.setInt(1, txId); rps.executeUpdate();
                                        }
                                        conn.commit();
                                        JOptionPane.showMessageDialog(this, "Insufficient balance -> rejected transaction");
                                        loadPendingTransactions();
                                        return;
                                    }
                                    try (PreparedStatement ups = conn.prepareStatement("UPDATE accounts SET balance = balance - ? WHERE id=?")) {
                                        ups.setDouble(1, amt); ups.setInt(2, accountId); ups.executeUpdate();
                                    }
                                }
                            }
                        } else {
                            // DEPOSIT
                            try (PreparedStatement ups = conn.prepareStatement("UPDATE accounts SET balance = balance + ? WHERE id=?")) {
                                ups.setDouble(1, amt); ups.setInt(2, accountId); ups.executeUpdate();
                            }
                        }

                        try (PreparedStatement mps = conn.prepareStatement("UPDATE transactions SET status='APPROVED', approved_by=NULL, approved_at=NOW() WHERE id=?")) {
                            mps.setInt(1, txId); mps.executeUpdate();
                        }
                        conn.commit();
                        JOptionPane.showMessageDialog(this,"Transaction approved and balance updated.");
                    } catch (SQLException ex) {
                        try { conn.rollback(); } catch (SQLException ignore) {}
                        throw ex;
                    } finally {
                        try { conn.setAutoCommit(originalAuto); } catch (SQLException ignore) {}
                    }
                }
            }
            loadPendingTransactions();
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this,"Approve error: "+ex.getMessage());
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ========= main =========
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SimpleBankAppRunner app = new SimpleBankAppRunner();
            app.setVisible(true);
        });
    }
}
