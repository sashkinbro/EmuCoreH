package View.License;

import Utils.Resources.ResourceLoader;
import View.TabLayout;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.jdesktop.swingx.VerticalLayout;

public class LicenseTab extends TabLayout {
    private final ResourceLoader resourceLoader = ResourceLoader.getInstance();
    private JScrollPane scrollPane;
    private JTextArea textArea;

    public LicenseTab() {
        initComponents();
    }

    private void initComponents() {
        this.setLayout(new VerticalLayout());
        setSize();

        textArea = new JTextArea(resourceLoader.get("license_text"));
        textArea.setEditable(false);
        textArea.setColumns(80);
        textArea.setLineWrap(true);
        textArea.setRows(47);
        textArea.setWrapStyleWord(true);
        scrollPane = new JScrollPane(textArea);
        this.add(scrollPane);
    }
}