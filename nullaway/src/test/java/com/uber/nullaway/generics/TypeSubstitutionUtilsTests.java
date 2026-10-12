package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests copying mutable javac types and restoring their nullability annotations.
 *
 * <p>These behaviors require types created by an active javac compilation, so each test compiles a
 * small source file with an assertion checker. Special field or method names in that source select
 * the operation that the checker exercises; the assertions themselves run inside the checker while
 * the corresponding javac types are available.
 */
@RunWith(JUnit4.class)
public class TypeSubstitutionUtilsTests {

  @Test
  public void replaceUnboundedWildcardUpperBoundDoesNotMutateFormalTypeVariable() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> typeVarField;
            }
            """)
        .doTest();
  }

  @Test
  public void replaceCapturedTypeWildcardReturnsDetachedCapture() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> capturedTypeField;
            }
            """)
        .doTest();
  }

  @Test
  public void cloneTypeWithMetadataReturnsDetachedMutableTypes() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> typeVarMetadataField;
              Test<?> capturedTypeMetadataField;
            }
            """)
        .doTest();
  }

  @Test
  public void restoreWildcardAnnotationsFromImplicitUpperBound() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T extends Test<T>> {
              Test<?> wildcardAnnotationField;
            }
            """)
        .doTest();
  }

  @Test
  public void restoreWildcardAnnotationsWithoutFormalLeavesWildcardUnchanged() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T extends Test<T>> {
              Test<?> wildcardWithoutFormalField;
            }
            """)
        .doTest();
  }

  @Test
  public void restoreUnchangedTypeListPreservesIdentity() {
    compileTypeListRestoration("unchanged", -1);
  }

  @Test
  public void restoreTypeListChangedFirstPreservesOrderAndAnnotations() {
    compileTypeListRestoration("first", 0);
  }

  @Test
  public void restoreTypeListChangedMiddlePreservesOrderAndAnnotations() {
    compileTypeListRestoration("middle", 1);
  }

  @Test
  public void restoreTypeListChangedLastPreservesOrderAndAnnotations() {
    compileTypeListRestoration("last", 2);
  }

  /** Compiles a marked parameter list with an optional nullable annotation to restore. */
  private void compileTypeListRestoration(String methodName, int changedPosition) {
    CompilationTestHelper.newInstance(TypeListRestorationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "import org.jspecify.annotations.Nullable;",
            "@Target(ElementType.TYPE_USE) @interface Marker {}",
            "class Test {",
            "  void "
                + methodName
                + "("
                + (changedPosition == 0 ? "@Nullable " : "")
                + "@Marker String first, "
                + (changedPosition == 1 ? "@Nullable " : "")
                + "@Marker Integer middle, "
                + (changedPosition == 2 ? "@Nullable " : "")
                + "@Marker Long last) {}",
            "}")
        .doTest();
  }

  /** Checks parameter-list restoration using compiler-attributed types and detached inputs. */
  @BugPattern(summary = "Checks restored type list identity and order", severity = SUGGESTION)
  public static final class TypeListRestorationChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /** Restores the selected parameter and verifies identity, annotations, and input isolation. */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      int changedPosition;
      switch (tree.getName().toString()) {
        case "unchanged" -> changedPosition = -1;
        case "first" -> changedPosition = 0;
        case "middle" -> changedPosition = 1;
        case "last" -> changedPosition = 2;
        default -> {
          return NO_MATCH;
        }
      }
      Config config =
          new NullAway(
                  ErrorProneFlags.builder()
                      .putFlag("NullAway:AnnotatedPackages", "com.uber")
                      .putFlag("NullAway:JSpecifyMode", "true")
                      .putFlag("NullAway:JSpecifyExperimental", "true")
                      .build())
              .getConfig();
      Type.MethodType source = ASTHelpers.getSymbol(tree).type.asMethodType();
      ListBuffer<Type> targetParameters = new ListBuffer<>();
      for (int i = 0; i < source.argtypes.size(); i++) {
        Type parameter = source.argtypes.get(i);
        assertThat(TypeSubstitutionUtils.hasNullableRoot(parameter, config))
            .isEqualTo(i == changedPosition);
        targetParameters.append(
            i == changedPosition
                ? TypeSubstitutionUtils.removeNullableAnnotation(parameter, config)
                : parameter);
      }
      Type.MethodType target =
          new Type.MethodType(
              targetParameters.toList(), source.restype, source.thrown, source.tsym);
      java.util.List<List<Type>> sourceCells = snapshotCells(source.argtypes);
      java.util.List<List<Type>> targetCells = snapshotCells(target.argtypes);
      java.util.List<Type> sourceHeads = java.util.List.copyOf(source.argtypes);
      java.util.List<Type> targetHeads = java.util.List.copyOf(target.argtypes);

      Type.MethodType restored =
          (Type.MethodType)
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, target, config);
      if (changedPosition == -1) {
        assertThat(restored).isSameInstanceAs(target);
        assertThat(restored.argtypes).isSameInstanceAs(target.argtypes);
      } else {
        assertThat(restored).isNotSameInstanceAs(target);
        assertThat(restored.argtypes).isNotSameInstanceAs(target.argtypes);
      }
      assertThat(restored.argtypes).hasSize(3);
      assertThat(restored.restype).isSameInstanceAs(target.restype);
      assertThat(restored.thrown).isSameInstanceAs(target.thrown);
      for (int i = 0; i < restored.argtypes.size(); i++) {
        Type parameter = restored.argtypes.get(i);
        assertThat(parameter.tsym).isSameInstanceAs(sourceHeads.get(i).tsym);
        assertThat(parameter.getAnnotationMirrors())
            .containsExactlyElementsIn(sourceHeads.get(i).getAnnotationMirrors());
        assertThat(TypeSubstitutionUtils.hasNullableRoot(parameter, config))
            .isEqualTo(i == changedPosition);
        assertThat(sourceHeads.get(i).getAnnotationMirrors()).hasSize(i == changedPosition ? 2 : 1);
        assertThat(TypeSubstitutionUtils.hasNullableRoot(sourceHeads.get(i), config))
            .isEqualTo(i == changedPosition);
        assertThat(
                targetHeads.get(i).getAnnotationMirrors().stream()
                    .map(annotation -> annotation.type.tsym.getQualifiedName().toString())
                    .toList())
            .containsExactly("Marker");
        if (i == changedPosition) {
          assertThat(parameter).isNotSameInstanceAs(targetHeads.get(i));
        } else {
          assertThat(parameter).isSameInstanceAs(targetHeads.get(i));
        }
      }
      assertUnmodifiedCells(source.argtypes, sourceCells, sourceHeads);
      assertUnmodifiedCells(target.argtypes, targetCells, targetHeads);
      assertThat(
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, restored, config))
          .isSameInstanceAs(restored);
      return NO_MATCH;
    }

    /** Saves every list cell, including the terminal empty cell, without modifying the list. */
    private static java.util.List<List<Type>> snapshotCells(List<Type> types) {
      java.util.List<List<Type>> cells = new java.util.ArrayList<>();
      for (List<Type> cursor = types; ; cursor = cursor.tail) {
        cells.add(cursor);
        if (cursor.isEmpty()) {
          return cells;
        }
      }
    }

    /** Verifies that restoration changed neither the heads nor tails of an input list. */
    private static void assertUnmodifiedCells(
        List<Type> types, java.util.List<List<Type>> cells, java.util.List<Type> heads) {
      assertThat(types).isSameInstanceAs(cells.get(0));
      for (int i = 0; i < heads.size(); i++) {
        assertThat(cells.get(i).head).isSameInstanceAs(heads.get(i));
        assertThat(cells.get(i).tail).isSameInstanceAs(cells.get(i + 1));
      }
      assertThat(cells.get(heads.size())).isEmpty();
    }
  }

  /**
   * Checker that exercises javac type copying and annotation restoration.
   *
   * <p>The fields named {@code typeVarField} and {@code capturedTypeField} exercise the two public
   * replacement helpers. The fields named {@code typeVarMetadataField} and {@code
   * capturedTypeMetadataField} exercise the metadata-copying path used by {@link
   * TypeSubstitutionUtils#typeWithAnnot}. The fields named {@code wildcardAnnotationField} and
   * {@code wildcardWithoutFormalField} exercise annotation restoration from unbounded wildcards,
   * with and without a corresponding formal type variable.
   */
  @BugPattern(
      summary = "Checks javac type copying and annotation restoration",
      severity = SUGGESTION)
  public static final class TypeCopyIsolationChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {

    private static final String TYPE_VAR_FIELD = "typeVarField";
    private static final String CAPTURED_TYPE_FIELD = "capturedTypeField";
    private static final String TYPE_VAR_METADATA_FIELD = "typeVarMetadataField";
    private static final String CAPTURED_TYPE_METADATA_FIELD = "capturedTypeMetadataField";
    private static final String WILDCARD_ANNOTATION_FIELD = "wildcardAnnotationField";
    private static final String WILDCARD_WITHOUT_FORMAL_FIELD = "wildcardWithoutFormalField";

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String fieldName = tree.getName().toString();
      TestTypeContext testTypeContext = createTestTypeContext(tree, state);
      switch (fieldName) {
        case TYPE_VAR_FIELD -> checkUnboundedWildcardReplacement(testTypeContext);
        case CAPTURED_TYPE_FIELD -> checkCapturedWildcardReplacement(testTypeContext, tree, state);
        case TYPE_VAR_METADATA_FIELD -> checkTypeVariableMetadataCopy(testTypeContext);
        case CAPTURED_TYPE_METADATA_FIELD ->
            checkCapturedTypeMetadataCopy(testTypeContext, tree, state);
        case WILDCARD_ANNOTATION_FIELD ->
            checkWildcardAnnotationRestoration(testTypeContext, false);
        case WILDCARD_WITHOUT_FORMAL_FIELD ->
            checkWildcardAnnotationRestoration(testTypeContext, true);
        default -> {
          throw new RuntimeException("Unknown field name: " + fieldName);
        }
      }
      return NO_MATCH;
    }

    /**
     * Extracts the compiler types shared by the test scenarios from a synthetic {@code Test<?>}
     * field.
     */
    private static TestTypeContext createTestTypeContext(VariableTree tree, VisitorState state) {
      Type.ClassType fieldType = (Type.ClassType) ASTHelpers.getType(tree);
      Type.WildcardType sourceWildcard = (Type.WildcardType) fieldType.getTypeArguments().head;
      Type.TypeVar formalTypeVariable = (Type.TypeVar) fieldType.tsym.type.getTypeArguments().head;
      Type originalUpperBound = formalTypeVariable.getUpperBound();
      Type nullableAnnotationType = GenericsChecks.getSyntheticNullableAnnotType(state);
      Type updatedUpperBound =
          TypeSubstitutionUtils.typeWithAnnot(originalUpperBound, nullableAnnotationType);
      Type.WildcardType unboundedWildcard =
          new Type.WildcardType(
              sourceWildcard.type, BoundKind.UNBOUND, sourceWildcard.tsym, formalTypeVariable);
      return new TestTypeContext(
          sourceWildcard,
          formalTypeVariable,
          originalUpperBound,
          updatedUpperBound,
          nullableAnnotationType,
          unboundedWildcard);
    }

    /**
     * Checks that replacing an implicit unbounded-wildcard bound copies its formal type variable.
     */
    private static void checkUnboundedWildcardReplacement(TestTypeContext context) {
      Type.WildcardType updatedWildcard =
          TypeSubstitutionUtils.replaceUnboundedWildcardUpperBound(
              context.unboundedWildcard(), context.updatedUpperBound());
      assertThat(context.formalTypeVariable().getUpperBound())
          .isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedWildcard).isNotSameInstanceAs(context.unboundedWildcard());
      assertThat(updatedWildcard.bound).isNotSameInstanceAs(context.formalTypeVariable());
      assertThat(updatedWildcard.bound.tsym).isSameInstanceAs(context.formalTypeVariable().tsym);
      assertThat(updatedWildcard.bound.lower).isSameInstanceAs(context.formalTypeVariable().lower);
      assertThat(updatedWildcard.bound.getUpperBound())
          .isSameInstanceAs(context.updatedUpperBound());
    }

    /** Checks that adding metadata to a type variable does not share its mutable upper bound. */
    private static void checkTypeVariableMetadataCopy(TestTypeContext context) {
      Type.TypeVar updatedTypeVariable =
          (Type.TypeVar)
              TypeSubstitutionUtils.typeWithAnnot(
                  context.formalTypeVariable(), context.nullableAnnotationType());
      updatedTypeVariable.setUpperBound(context.updatedUpperBound());
      assertThat(context.formalTypeVariable().getUpperBound())
          .isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedTypeVariable.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
      assertThat(updatedTypeVariable.baseType())
          .isSameInstanceAs(context.formalTypeVariable().baseType());
      assertThat(context.formalTypeVariable().getAnnotationMirrors()).isEmpty();
      assertThat(updatedTypeVariable.getAnnotationMirrors()).isNotEmpty();
    }

    /**
     * Checks that an extends wildcard receives annotations from an unbounded wildcard's implicit
     * upper bound, leaving compiler-owned types intact. Without a formal, restoration is skipped.
     */
    private static void checkWildcardAnnotationRestoration(
        TestTypeContext context, boolean missingFormal) {
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:OnlyNullMarked", "true",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:HandleWildcardGenerics", "true")))
              .getConfig();
      Type.TypeVar annotatedFormal =
          TypeMetadataBuilder.TYPE_METADATA_BUILDER.createDetachedTypeVar(
              context.formalTypeVariable(), context.updatedUpperBound());
      Type.WildcardType source =
          new Type.WildcardType(
              context.sourceWildcard().type,
              BoundKind.UNBOUND,
              context.sourceWildcard().tsym,
              missingFormal ? null : annotatedFormal);
      Type.WildcardType target =
          new Type.WildcardType(
              context.originalUpperBound(), BoundKind.EXTENDS, context.sourceWildcard().tsym);
      Type.WildcardType restored =
          (Type.WildcardType)
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, target, config);
      if (missingFormal) {
        assertThat(restored).isSameInstanceAs(target);
      } else {
        assertThat(restored).isNotSameInstanceAs(target);
        assertThat(restored.type.getAnnotationMirrors()).hasSize(1);
        assertThat(restored.type.getAnnotationMirrors().head.type)
            .isSameInstanceAs(context.nullableAnnotationType());
      }
      assertThat(target.type.getAnnotationMirrors()).isEmpty();
      assertThat(annotatedFormal.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
      assertThat(context.formalTypeVariable().getUpperBound())
          .isSameInstanceAs(context.originalUpperBound());
    }

    /** Checks that replacing a capture's backing wildcard returns a fully detached capture. */
    private static void checkCapturedWildcardReplacement(
        TestTypeContext context, VariableTree tree, VisitorState state) {
      Type.CapturedType capturedType = createCapturedType(context, tree, state);
      Type.WildcardType replacementWildcard =
          new Type.WildcardType(
              context.updatedUpperBound(), BoundKind.EXTENDS, context.sourceWildcard().tsym);
      Type.CapturedType updatedCapture =
          TypeSubstitutionUtils.replaceCapturedTypeWildcard(capturedType, replacementWildcard);
      assertThat(updatedCapture).isNotSameInstanceAs(capturedType);
      assertThat(updatedCapture.tsym).isSameInstanceAs(capturedType.tsym);
      assertThat(updatedCapture.lower).isSameInstanceAs(capturedType.lower);
      assertThat(capturedType.wildcard).isSameInstanceAs(context.unboundedWildcard());
      assertThat(updatedCapture.wildcard).isSameInstanceAs(replacementWildcard);

      // A javac metadata clone delegates this setter to the original capture. Mutating the
      // replacement therefore proves that it is a genuinely detached object, not such a clone.
      updatedCapture.setUpperBound(context.updatedUpperBound());
      assertThat(capturedType.getUpperBound()).isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedCapture.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
    }

    /** Checks that adding metadata to a capture does not share its mutable upper bound. */
    private static void checkCapturedTypeMetadataCopy(
        TestTypeContext context, VariableTree tree, VisitorState state) {
      Type.CapturedType capturedType = createCapturedType(context, tree, state);
      Type.CapturedType updatedCapture =
          (Type.CapturedType)
              TypeSubstitutionUtils.typeWithAnnot(capturedType, context.nullableAnnotationType());
      updatedCapture.setUpperBound(context.updatedUpperBound());
      assertThat(capturedType.getUpperBound()).isSameInstanceAs(context.originalUpperBound());
      assertThat(updatedCapture.getUpperBound()).isSameInstanceAs(context.updatedUpperBound());
      assertThat(updatedCapture.baseType()).isSameInstanceAs(capturedType.baseType());
      assertThat(capturedType.getAnnotationMirrors()).isEmpty();
      assertThat(updatedCapture.getAnnotationMirrors()).isNotEmpty();
    }

    /** Creates the synthetic captured type used by both capture-copy scenarios. */
    private static Type.CapturedType createCapturedType(
        TestTypeContext context, VariableTree tree, VisitorState state) {
      Symbol owner = ASTHelpers.getSymbol(tree);
      return new Type.CapturedType(
          context.formalTypeVariable().tsym.name,
          owner,
          context.originalUpperBound(),
          state.getSymtab().botType,
          context.unboundedWildcard());
    }

    /** Compiler types derived from one synthetic field and shared by a single test scenario. */
    private record TestTypeContext(
        Type.WildcardType sourceWildcard,
        Type.TypeVar formalTypeVariable,
        Type originalUpperBound,
        Type updatedUpperBound,
        Type nullableAnnotationType,
        Type.WildcardType unboundedWildcard) {}
  }
}
