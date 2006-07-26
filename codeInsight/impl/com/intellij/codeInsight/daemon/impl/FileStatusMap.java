
package com.intellij.codeInsight.daemon.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.WeakHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class FileStatusMap {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.FileStatusMap");

  private final Project myProject;

  private final Map<Document,FileStatus> myDocumentToStatusMap = new WeakHashMap<Document, FileStatus>(); // all dirty if absent

  private final Key<RefCountHolder> REF_COUND_HOLDER_IN_EDITOR_DOCUMENT_KEY = Key.create("DaemonCodeAnalyzerImpl.REF_COUND_HOLDER_IN_EDITOR_DOCUMENT_KEY");
  private final Map<Document,Object> myDocumentsWithRefCountHolders = new WeakHashMap<Document, Object>(); // Document --> null
  private final Object myRefCountHolderLock = new Object();

  private static class FileStatus {
    private PsiElement dirtyScope; //Q: use WeakReference?
    private PsiElement overridenDirtyScope;
    private PsiElement localInspectionsDirtyScope;
    public boolean defensivelyMarked; // file marked dirty without knowlesdge of specific dirty region. Subsequent markScopeDirty can refine dirty scope, not extend it

    private FileStatus(PsiElement dirtyScope, PsiElement overridenDirtyScope, PsiElement inspectionDirtyScope) {
      this.dirtyScope = dirtyScope;
      this.overridenDirtyScope = overridenDirtyScope;
      localInspectionsDirtyScope = inspectionDirtyScope;
    }
  }

  public static final int NORMAL_HIGHLIGHTERS = 1;
  public static final int OVERRIDEN_MARKERS = 2;
  public static final int LOCAL_INSPECTIONS = 3;

  public FileStatusMap(@NotNull Project project) {
    myProject = project;
  }

  public void markAllFilesDirty() {
    synchronized(myDocumentToStatusMap){
      myDocumentToStatusMap.clear();
    }

    synchronized(myRefCountHolderLock){
      for (Document document : myDocumentsWithRefCountHolders.keySet()) {
        document.putUserData(REF_COUND_HOLDER_IN_EDITOR_DOCUMENT_KEY, null);
      }
      myDocumentsWithRefCountHolders.clear();
    }
  }

  public void markFileDirty(@NotNull Document document) {
    synchronized(myDocumentToStatusMap){
      myDocumentToStatusMap.remove(document);
    }

    synchronized(myRefCountHolderLock){
      document.putUserData(REF_COUND_HOLDER_IN_EDITOR_DOCUMENT_KEY, null);
      myDocumentsWithRefCountHolders.remove(document);
    }
  }

  public void markFileUpToDate(@NotNull Document document, int part) {
    synchronized(myDocumentToStatusMap){
      FileStatus status = myDocumentToStatusMap.get(document);
      if (status == null){
        PsiFile file = PsiDocumentManager.getInstance(myProject).getPsiFile(document);
        status = new FileStatus(file, file, file);
        myDocumentToStatusMap.put(document, status);
      }
      status.defensivelyMarked=false;
      if (part == NORMAL_HIGHLIGHTERS){
        status.dirtyScope = null;
      }
      else if (part == OVERRIDEN_MARKERS){
        status.overridenDirtyScope = null;
      }
      else if (part == LOCAL_INSPECTIONS){
        status.localInspectionsDirtyScope = null;
      }
      else{
        LOG.assertTrue(false);
      }
    }
  }

  public PsiElement getFileDirtyScope(@NotNull Document document, int part) {
    synchronized(myDocumentToStatusMap){
      FileStatus status = myDocumentToStatusMap.get(document);
      if (status == null){
        return PsiDocumentManager.getInstance(myProject).getPsiFile(document);
      }
      if (status.defensivelyMarked) {
        status.dirtyScope = status.localInspectionsDirtyScope = PsiDocumentManager.getInstance(myProject).getPsiFile(document);
        status.defensivelyMarked = false;
      }
      if (part == NORMAL_HIGHLIGHTERS){
        return status.dirtyScope;
      }
      else if (part == OVERRIDEN_MARKERS){
        return status.overridenDirtyScope;
      }
      else if (part == LOCAL_INSPECTIONS) {
        return status.localInspectionsDirtyScope;
      }
      else{
        LOG.assertTrue(false);
        return null;
      }
    }
  }

  public void markFileScopeDirtyDefensively(@NotNull PsiFile file) {
    // mark whole file dirty in case no subsequent PSI events will come, but file requires rehighlighting nevertheless
    // e.g. in the case of quick typing/backspacing char
    synchronized(myDocumentToStatusMap){
      Document document = PsiDocumentManager.getInstance(myProject).getCachedDocument(file);
      if (document == null) return;
      FileStatus status = myDocumentToStatusMap.get(document);
      if (status == null) return; // all dirty already
      status.defensivelyMarked = true;
    }
  }

  public void markFileScopeDirty(@NotNull Document document, @NotNull PsiElement scope) {
    synchronized(myDocumentToStatusMap) {
      FileStatus status = myDocumentToStatusMap.get(document);
      if (status == null) return; // all dirty already
      if (status.defensivelyMarked) {
        status.defensivelyMarked = false;
      }
      final PsiElement combined1 = combineScopes(status.dirtyScope, scope);
      status.dirtyScope = combined1 == null ? PsiDocumentManager.getInstance(myProject).getPsiFile(document) : combined1;
      final PsiElement combined2 = combineScopes(status.localInspectionsDirtyScope, scope);
      status.localInspectionsDirtyScope = combined2 == null ? PsiDocumentManager.getInstance(myProject).getPsiFile(document) : combined2;
      //status.overridenDirtyScope = combineScopes(status.overridenDirtyScope, scope);
    }
  }

  private static PsiElement combineScopes(PsiElement scope1, PsiElement scope2) {
    if (scope1 == null) return scope2;
    if (scope2 == null) return scope1;
    if (!scope1.isValid() || !scope2.isValid()) return null;
    final PsiElement commonParent = PsiTreeUtil.findCommonParent(scope1, scope2);
    if (commonParent instanceof PsiDirectory) return null;
    return commonParent;
  }

  public RefCountHolder getRefCountHolder(@NotNull Document document, @NotNull PsiFile file) {
    RefCountHolder refCountHolder;
    synchronized (myRefCountHolderLock) {
      refCountHolder = document.getUserData(REF_COUND_HOLDER_IN_EDITOR_DOCUMENT_KEY);
      if (refCountHolder == null) {
        refCountHolder = new RefCountHolder(file);
        document.putUserData(REF_COUND_HOLDER_IN_EDITOR_DOCUMENT_KEY, refCountHolder);
        myDocumentsWithRefCountHolders.put(document, null);
      }
    }
    return refCountHolder;
  }
}