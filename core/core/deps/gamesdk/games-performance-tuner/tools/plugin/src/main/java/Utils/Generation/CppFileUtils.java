/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package Utils.Generation;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.jetbrains.cidr.lang.psi.OCCppLinkageSpecification;
import com.jetbrains.cidr.lang.psi.OCFile;
import com.jetbrains.cidr.lang.psi.OCFunctionDefinition;
import com.jetbrains.cidr.lang.psi.OCReferenceElement;
import com.jetbrains.cidr.lang.quickfixes.OCImportSymbolFix;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class CppFileUtils {

  private final PsiFile psiFile;
  private final List<OCFunctionDefinition> methods;

  public CppFileUtils(PsiFile psiFile) {
    if (!(psiFile instanceof OCFile)) {
      throw new RuntimeException("Only Cpp files are allowed");
    }
    this.psiFile = psiFile;
    this.methods = new ArrayList<>();

    scanFile();
  }

  private Optional<PsiElement> hasMethod(OCFunctionDefinition methodSearched) {
    List<OCFunctionDefinition> methodsMatched = methods.stream().filter(
        method -> Objects.equals(methodSearched.getName(), method.getName())
    ).collect(Collectors.toList());
    if (methodsMatched.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(methodsMatched.get(0));
  }

  private void scanFile() {
    methods.clear();
    psiFile.accept(new PsiRecursiveElementVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        super.visitElement(element);
        if (element instanceof OCFunctionDefinition) {
          methods.add((OCFunctionDefinition) element);
        }
      }
    });
  }

  public Optional<PsiElement> findMethodAsChildren(PsiElement parentElement) {
    PsiElement functionDefinition = null;
    for (PsiElement childPsi : parentElement.getChildren()) {
      if (childPsi instanceof OCFunctionDefinition) {
        functionDefinition = childPsi;
        break;
      }
    }
    return Optional.ofNullable(functionDefinition);
  }

  public List<OCReferenceElement> getAllReferenceElementsInsideMethods(
      List<String> methodNames) {
    ArrayList<OCReferenceElement> referenceElements = new ArrayList<>();

    psiFile.accept(new PsiRecursiveElementVisitor() {
      String currentMethodName = "";

      @Override
      public void visitElement(PsiElement element) {
        super.visitElement(element);
        if ((element instanceof OCReferenceElement) &&
            methodNames.contains(currentMethodName)) {
          referenceElements.add((OCReferenceElement) element);
        } else if (element instanceof OCFunctionDefinition) {
          currentMethodName = ((OCFunctionDefinition) element).getName();
        }
      }
    });
    return referenceElements;
  }

  public void fixImports(List<OCReferenceElement> referenceElements) {
    Project project = psiFile.getProject();
    referenceElements
        .forEach(referenceElement ->
            (new OCImportSymbolFix(referenceElement)).fixFirstItem(project, psiFile));
  }

  // Finds method definition of a C linkage.
  public Optional<PsiElement> findElement(PsiElement psiElement) {
    if (psiElement instanceof OCCppLinkageSpecification) {
      Optional<PsiElement> methodDefinition = findMethodAsChildren(psiElement);
      if (methodDefinition.isPresent()) {
        return hasMethod((OCFunctionDefinition) methodDefinition.get());
      } else {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
