/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python;

import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.projectRoots.impl.MockSdk;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.MultiMap;
import com.jetbrains.python.codeInsight.typing.PyTypeShed;
import com.jetbrains.python.codeInsight.userSkeletons.PyUserSkeletonsUtil;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * @author yole
 */
public class PythonMockSdk {
  @NonNls private static final String MOCK_SDK_NAME = "Mock Python SDK";

  private PythonMockSdk() {
  }

  public static Sdk create(final String version, @NotNull final VirtualFile... additionalRoots) {
    final String mock_path = PythonTestUtil.getTestDataPath() + "/MockSdk" + version + "/";

    String sdkHome = new File(mock_path, "bin/python" + version).getPath();

    MultiMap<OrderRootType, VirtualFile> roots = MultiMap.create();

    File libPath = new File(mock_path, "Lib");
    if (libPath.exists()) {
      roots.putValue(OrderRootType.CLASSES, LocalFileSystem.getInstance().refreshAndFindFileByIoFile(libPath));
    }

    roots.putValue(OrderRootType.CLASSES, PyUserSkeletonsUtil.getUserSkeletonsDirectory());

    final LanguageLevel level = LanguageLevel.fromPythonVersion(version);
    final VirtualFile typeShedDir = PyTypeShed.INSTANCE.getDirectory();
    assert typeShedDir != null;
    PyTypeShed.INSTANCE.findRootsForLanguageLevel(level).forEach(path -> {
      final VirtualFile file = typeShedDir.findFileByRelativePath(path);
      if (file != null) {
        roots.putValue(OrderRootType.CLASSES, file);
      }
    });

    String mock_stubs_path = mock_path + PythonSdkUtil.SKELETON_DIR_NAME;
    roots.putValue(PythonSdkUtil.BUILTIN_ROOT_TYPE, LocalFileSystem.getInstance().refreshAndFindFileByPath(mock_stubs_path));

    for (final VirtualFile root : additionalRoots) {
      roots.putValue(OrderRootType.CLASSES, root);
    }

    MockSdk sdk = new MockSdk(MOCK_SDK_NAME + " " + version, sdkHome, "Python " + version + " Mock SDK", roots, new PyMockSdkType());

    // com.jetbrains.python.psi.resolve.PythonSdkPathCache.getInstance() corrupts SDK, so have to clone
    return sdk.clone();
  }

  private static class PyMockSdkType extends SdkType {

    public PyMockSdkType() {
      super(PyNames.PYTHON_SDK_ID_NAME);
    }

    @Nullable
    @Override
    public String suggestHomePath() {
      return null;
    }

    @Override
    public boolean isValidSdkHome(String path) {
      return true;
    }

    @NotNull
    @Override
    public String suggestSdkName(@Nullable String currentSdkName, String sdkHome) {
      return "Python";
    }

    @Nullable
    @Override
    public AdditionalDataConfigurable createAdditionalDataConfigurable(@NotNull SdkModel sdkModel, @NotNull SdkModificator sdkModificator) {
      return null;
    }

    @NotNull
    @Override
    public String getPresentableName() {
      return "Python";
    }

    @Override
    public void saveAdditionalData(@NotNull SdkAdditionalData additionalData, @NotNull Element additional) {

    }
  }
}
