package com.intellij.util.net.ssl;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileTypeDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.AnActionButtonRunnable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Consumer;
import com.intellij.util.containers.HashSet;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static com.intellij.util.net.ssl.CertificateUtil.getCommonName;
import static com.intellij.util.net.ssl.ConfirmingTrustManager.MutableTrustManager;

/**
 * @author Mikhail Golubev
 */
public class CertificateConfigurable implements SearchableConfigurable, Configurable.NoScroll, CertificateListener {
  private static final FileTypeDescriptor CERTIFICATE_DESCRIPTOR = new FileTypeDescriptor("Choose Certificate", ".crt", ".cer", ".pem");
  @NonNls public static final String EMPTY_PANEL = "empty.panel";

  private JPanel myRootPanel;
  private JBCheckBox myCheckHostname;
  private JBCheckBox myCheckValidityPeriod;

  private JPanel myCertificatesListPanel;
  private JPanel myDetailsPanel;
  private JPanel myEmptyPanel;
  private MutableTrustManager myTrustManager;

  private Tree myTree;
  private CertificateTreeBuilder myTreeBuilder;
  private Set<X509Certificate> myCertificates = new HashSet<X509Certificate>();

  private void initializeUI() {
    myTree = new Tree();
    myTreeBuilder = new CertificateTreeBuilder(myTree);

    // not fully functional by now
    myCheckHostname.setVisible(false);
    myCheckValidityPeriod.setVisible(false);

    myTrustManager = CertificateManager.getInstance().getCustomTrustManager();
    // show newly added certificates
    myTrustManager.addListener(this);

    myTree.getEmptyText().setText("No certificates");
    myTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    myTree.setRootVisible(false);
    //myTree.setShowsRootHandles(false);

    ToolbarDecorator decorator = ToolbarDecorator.createDecorator(myTree).disableUpDownActions();
    decorator.setAddAction(new AnActionButtonRunnable() {
      @Override
      public void run(AnActionButton button) {
        // show choose file dialog, add certificate
        FileChooser.chooseFile(CERTIFICATE_DESCRIPTOR, null, null, new Consumer<VirtualFile>() {
          @Override
          public void consume(VirtualFile file) {
            String path = file.getPath();
            X509Certificate certificate = CertificateUtil.loadX509Certificate(path);
            if (certificate == null) {
              Messages.showErrorDialog(myRootPanel, "Malformed X509 server certificate", "Not Imported");
            }
            else if (myCertificates.contains(certificate)) {
              Messages.showWarningDialog(myRootPanel, "Certificate already exists", "Not Imported");
            }
            else {
              myCertificates.add(certificate);
              myTreeBuilder.addCertificate(certificate);
              addCertificatePanel(certificate);
              myTreeBuilder.selectCertificate(certificate);
            }
          }
        });
      }
    }).setRemoveAction(new AnActionButtonRunnable() {
      @Override
      public void run(AnActionButton button) {
        // allow to delete several certificates at once
        for (X509Certificate certificate : myTreeBuilder.getSelectedCertificates(true)) {
          myCertificates.remove(certificate);
          myTreeBuilder.removeCertificate(certificate);
        }
        if (myCertificates.isEmpty()) {
          showCard(EMPTY_PANEL);
        }
        else {
          myTreeBuilder.selectFirstCertificate();
        }
      }
    });

    myTree.addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        X509Certificate certificate = myTreeBuilder.getFirstSelectedCertificate(true);
        if (certificate != null) {
          showCard(getCardName(certificate));
        }
      }
    });
    myCertificatesListPanel.add(decorator.createPanel(), BorderLayout.CENTER);
  }

  private void showCard(@NotNull String cardName) {
    ((CardLayout)myDetailsPanel.getLayout()).show(myDetailsPanel, cardName);
  }

  private void addCertificatePanel(@NotNull X509Certificate certificate) {
    String uniqueName = getCardName(certificate);
    JPanel infoPanel = new CertificateInfoPanel(certificate);
    UIUtil.addInsets(infoPanel, UIUtil.PANEL_REGULAR_INSETS);
    JBScrollPane scrollPane = new JBScrollPane(infoPanel);
    //scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    myDetailsPanel.add(scrollPane, uniqueName);
  }

  private static String getCardName(@NotNull X509Certificate certificate) {
    return certificate.getSubjectX500Principal().getName();
  }

  @NotNull
  @Override
  public String getId() {
    return "http.certificates";
  }

  @Nullable
  @Override
  public Runnable enableSearch(String option) {
    return null;
  }

  @Nls
  @Override
  public String getDisplayName() {
    return "Server Certificates";
  }

  @Nullable
  @Override
  public String getHelpTopic() {
    return null;
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    // lazily initialized to ensure that disposeUIResources() will be called, if
    // tree builder was created
    initializeUI();
    return myRootPanel;
  }

  @Override
  public boolean isModified() {
    CertificateManager.Config state = CertificateManager.getInstance().getState();
    return myCheckHostname.isSelected() != state.checkHostname ||
           myCheckValidityPeriod.isSelected() != state.checkValidity ||
           !myCertificates.equals(new HashSet<X509Certificate>(myTrustManager.getCertificates()));
  }

  @Override
  public void apply() throws ConfigurationException {
    List<X509Certificate> existing = myTrustManager.getCertificates();

    Set<X509Certificate> added = new HashSet<X509Certificate>(myCertificates);
    added.removeAll(existing);

    Set<X509Certificate> removed = new HashSet<X509Certificate>(existing);
    removed.removeAll(myCertificates);

    for (X509Certificate certificate : added) {
      if (!myTrustManager.addCertificate(certificate)) {
        throw new ConfigurationException("Cannot add certificate for " + getCommonName(certificate), "Cannot Add Certificate");
      }
    }

    for (X509Certificate certificate : removed) {
      if (!myTrustManager.removeCertificate(certificate)) {
        throw new ConfigurationException("Cannot remove certificate for " + getCommonName(certificate), "Cannot Remove Certificate");
      }
    }
    CertificateManager.Config state = CertificateManager.getInstance().getState();
    state.checkHostname = myCheckHostname.isSelected();
    state.checkValidity = myCheckValidityPeriod.isSelected();
  }

  @Override
  public void reset() {
    List<X509Certificate> original = myTrustManager.getCertificates();
    myTreeBuilder.reset(original);

    myCertificates.clear();
    myCertificates.addAll(original);

    myDetailsPanel.removeAll();
    myDetailsPanel.add(myEmptyPanel, EMPTY_PANEL);

    // fill lower panel with cards
    for (X509Certificate certificate : original) {
      addCertificatePanel(certificate);
    }

    if (!myCertificates.isEmpty()) {
      myTreeBuilder.selectFirstCertificate();
    }

    CertificateManager.Config state = CertificateManager.getInstance().getState();
    myCheckHostname.setSelected(state.checkHostname);
    myCheckValidityPeriod.setSelected(state.checkValidity);
  }

  @Override
  public void disposeUIResources() {
    Disposer.dispose(myTreeBuilder);
    myTrustManager.removeListener(this);
  }

  @Override
  public void certificateAdded(final X509Certificate certificate) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        if (myTreeBuilder != null && !myCertificates.contains(certificate)) {
          myCertificates.add(certificate);
          myTreeBuilder.addCertificate(certificate);
          addCertificatePanel(certificate);
        }
      }
    });
  }

  @Override
  public void certificateRemoved(final X509Certificate certificate) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      public void run() {
        if (myTreeBuilder != null && myCertificates.contains(certificate)) {
          myCertificates.remove(certificate);
          myTreeBuilder.removeCertificate(certificate);
        }
      }
    });
  }
}
