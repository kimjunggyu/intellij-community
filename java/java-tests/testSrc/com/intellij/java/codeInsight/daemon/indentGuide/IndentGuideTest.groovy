/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.intellij.java.codeInsight.daemon.indentGuide

import com.intellij.openapi.editor.IndentsModel
import com.intellij.openapi.editor.impl.IndentsModelImpl
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.util.ArrayUtilRt
import org.jetbrains.annotations.NotNull
/**
 * @author Denis Zhdanov
 */
class IndentGuideTest extends BaseIndentGuideTest {

  @Override
  protected void setUp() throws Exception {
    super.setUp()
  }

  void "test indent guides which cross commented code between comment mark and comment text"() {
    // IDEA-99572.
    doTest(
"""\
class Test {
  void test() {
  |  //test();
  |  if (true) {
  |  |  int i = 1;
  |  |  
//|  |    int k;
  |  |  int j = 1;
  |  }
  }
}
"""
    )
  }

  void "test no indent guides in commented regions"() {
    doTest(
"""\
class Test {
  void test() {
  |  return;
//|    if (true) {
//|      int i1 = 1;
//|      int i2 = 2;
//|      if (true) {
//|        int j1 = 1;
//|        int j2 = 2;
//|      }
//|    }
//|  int k = 1;
  }
}
"""
    )
  }

  void "test indent guide which starts on comment line"() {
    doTest(
"""\
class Test {
  void test(int i) {
  |  switch (i) {
  |  |//
  |  |  case 1:
  |  |  case 2:
  |  }
  }
}
"""
    )
  }
  
  void "test no indent guide for javadoc"() {
    doTest(
"""\
class Test {
  /**
   * doc
   */
  int i;
}
"""
    )
  }
  
  void "test no unnecessary guide for non-first line comments"() {
    doTest(
      """\
class Test {
  void test() {
  |  //int i1;
  |  //int i2;
  |  return;
  }
}
"""
    )
  }
  
  void "test block comment and inner indents"() {
    doTest(
      """\
class Test {
  int test() {
  |  return 1 /*{
  |    int test2() {
  |      int i1;
  |    }
  |    int i2;
  |  }*/;
  }
}
"""
    )
  }
  
  void "test empty comment does not break indents"() {
    doTest(
"""\
class Test {
  void m() {
  |
//|
  |
  |  int v;
  }
}
"""      
    )
  }

  void testCodeConstructStartLine() {
    myFixture.configureByText("${getTestName(false)}.java", """\
class C {
  void m() 
  {
  <caret>  int a;
  }
}
""")
    CodeInsightTestFixtureImpl.instantiateAndRun(myFixture.file, myFixture.editor, ArrayUtilRt.EMPTY_INT_ARRAY, false)
    def guide = myFixture.editor.indentsModel.caretIndentGuide
    assertNotNull guide
    assert guide.toString() == "2 (1-2-4)"
  }

  private void doTest(@NotNull String text) {
    doTest(text, { IndentModelGuidesProvider.create(it) })
  }

  private static class IndentModelGuidesProvider implements IndentGuidesProvider {

    private final IndentsModel myIndentsModel
    private final List<Guide> myGuides

    private IndentModelGuidesProvider(IndentsModel indentsModel, List<Guide> guides) {
      myIndentsModel = indentsModel
      myGuides = guides
    }

    private static IndentModelGuidesProvider create(CodeInsightTestFixture fixture) {
      def indentsModel = fixture.editor.indentsModel
      def guides = extractIndentGuides(indentsModel)
      return new IndentModelGuidesProvider(indentsModel, guides)
    }

    private static List<Guide> extractIndentGuides(IndentsModel indentsModel) {
      (indentsModel as IndentsModelImpl).indents.collect {new Guide(it.startLine, it.endLine, it.indentLevel)}
    }

    @NotNull
    @Override
    List<Guide> getGuides() {
      return myGuides
    }

    @Override
    Integer getIndentAt(int startLine, int endLine) {
      return myIndentsModel.getDescriptor(startLine, endLine)?.indentLevel
    }
  }
}
