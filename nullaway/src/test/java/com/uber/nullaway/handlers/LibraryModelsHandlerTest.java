/*
 * Copyright (C) 2026. Uber Technologies
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

package com.uber.nullaway.handlers;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.uber.nullaway.LibraryModels.MethodRef.methodRef;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.io.ByteStreams;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.LibraryModels.MethodRef;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import com.uber.nullaway.libmodel.NestedAnnotationInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibraryModelsHandlerTest {

  private static final String RESOURCE_NAME = "model.astubx";
  private static final ThreadLocal<Consumer<Fixture>> MODEL_ASSERTIONS = new ThreadLocal<>();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * Verifies that closing one classloader cannot invalidate a resource stream opened by a second
   * classloader that uses {@link LibraryModelsHandler#openResourceWithoutCaching(URL)}. This models
   * overlapping compilations whose classloaders read a model from the same JAR; see <a
   * href="https://github.com/uber/NullAway/issues/1829">https://github.com/uber/NullAway/issues/1829</a>.
   */
  @Test
  public void uncachedResourceStreamSurvivesClosingAnotherClassLoader() throws Exception {
    byte[] expected = new byte[1_000_000];
    Arrays.fill(expected, (byte) 42);
    File jarFile = createJarContainingResource(expected);
    URL[] urls = {jarFile.toURI().toURL()};

    try (URLClassLoader loaderA = URLClassLoader.newInstance(urls, null);
        URLClassLoader loaderB = URLClassLoader.newInstance(urls, null);
        // Compilation B starts reading its model through an independently owned JAR handle.
        InputStream streamB =
            Objects.requireNonNull(
                LibraryModelsHandler.openResourceWithoutCaching(
                    loaderB.getResource(RESOURCE_NAME)))) {
      // Compilation A opens the same resource through URLClassLoader's shared JAR cache.
      try (InputStream streamA =
          Objects.requireNonNull(loaderA.getResourceAsStream(RESOURCE_NAME))) {
        assertThat(streamA.read()).isNotEqualTo(-1);
      }
      // Finishing compilation A closes its classloader while compilation B is still reading.
      loaderA.close();
      // Check that the read from compilation B completes successfully
      assertThat(ByteStreams.toByteArray(streamB)).isEqualTo(expected);
    }
  }

  @Test
  public void methodRefConversionIsSharedAcrossAllTables() {
    compile(
        fixture -> {
          MethodRef ref = methodRef("com.uber.Test", "choose(java.lang.Object)");
          Object view = optimizedModels(modelsFor(ref), fixture.state().context);
          CountingMethodSymbol symbol = new CountingMethodSymbol(fixture.method(ref.fullMethodSig));
          for (int i = 0; i < 3; i++) {
            assertAllTables(view, symbol, true);
          }
          assertThat(symbol.conversions).isEqualTo(1);

          // Even equivalent declarations are distinct cache keys when their symbols differ.
          CountingMethodSymbol distinct =
              new CountingMethodSymbol(fixture.method(ref.fullMethodSig));
          assertAllTables(view, distinct, true);
          assertThat(distinct.conversions).isEqualTo(1);
          assertThat(symbol.conversions).isEqualTo(1);

          // The memo belongs to the optimized view, not a static or process-wide symbol cache.
          Object otherView = optimizedModels(modelsFor(ref), fixture.state().context);
          assertAllTables(otherView, symbol, true);
          assertThat(symbol.conversions).isEqualTo(2);
        });
  }

  @Test
  public void overloadsVarargsGenericsAndConstructorsKeepDistinctDescriptors() {
    compile(
        fixture -> {
          String[] signatures = {
            "choose(java.lang.Object)",
            "choose(java.lang.String)",
            "choose(java.lang.Object...)",
            "<T>generic(T)",
            "Test()"
          };
          MethodRef[] refs =
              Arrays.stream(signatures)
                  .map(signature -> methodRef("com.uber.Test", signature))
                  .toArray(MethodRef[]::new);
          Object view = optimizedModels(modelsFor(refs), fixture.state().context);
          assertThat(fixture.method("choose(java.lang.Object...)").isVarArgs()).isTrue();
          for (String signature : signatures) {
            CountingMethodSymbol symbol = new CountingMethodSymbol(fixture.method(signature));
            assertAllTables(view, symbol, true);
            assertAllTables(view, symbol, true);
            assertThat(symbol.conversions).isEqualTo(1);
          }
          CountingMethodSymbol overload = new CountingMethodSymbol(fixture.method("choose(int)"));
          assertAllTables(view, overload, false);
          assertAllTables(view, overload, false);
          assertThat(overload.conversions).isEqualTo(1);
        });
  }

  @Test
  public void missingNameDoesNotConvertOrTraverseSupertypes() {
    compile(
        fixture -> {
          Object view =
              optimizedModels(
                  modelsFor(methodRef("com.uber.Test", "choose(java.lang.Object)")),
                  fixture.state().context);
          CountingMethodSymbol missing = new CountingMethodSymbol(fixture.method("unmodeled()"));
          assertAllTables(view, missing, false);
          assertThat(
                  invoke(
                      view,
                      "hasNullableReturn",
                      new Class<?>[] {Symbol.MethodSymbol.class, Types.class, boolean.class},
                      missing,
                      null,
                      true))
              .isEqualTo(false);
          assertThat(missing.conversions).isEqualTo(0);
        });
  }

  @Test
  public void declarationTypeReplacementInvalidatesHitsAndMissesAcrossTables() {
    compile(
        fixture -> {
          Object view =
              optimizedModels(
                  modelsFor(methodRef("com.uber.Test", "choose(java.lang.Object)")),
                  fixture.state().context);
          CountingMethodSymbol symbol = new CountingMethodSymbol(fixture.method("choose(int)"));
          Type original = symbol.type;
          assertAllTables(view, symbol, false);
          assertThat(symbol.conversions).isEqualTo(1);

          symbol.type = fixture.method("choose(java.lang.Object)").type;
          assertAllTables(view, symbol, true);
          assertAllTables(view, symbol, true);
          assertThat(symbol.conversions).isEqualTo(2);

          symbol.type = fixture.method("choose(java.lang.String)").type;
          assertAllTables(view, symbol, false);
          assertThat(symbol.conversions).isEqualTo(3);

          symbol.type = original;
          assertAllTables(view, symbol, false);
          assertThat(symbol.conversions).isEqualTo(4);
        });
  }

  @Test
  public void cachedDescriptorsDoNotChangeExactOrInheritedLookupPolicy() {
    compile(
        fixture -> {
          MethodRef base = methodRef("com.uber.Base", "choose(java.lang.Object)");
          Object inherited = optimizedModels(modelsFor(base), fixture.state().context);
          Symbol.MethodSymbol override = fixture.method(base.fullMethodSig);
          Types types = fixture.state().getTypes();
          Class<?>[] returnParameters = {Symbol.MethodSymbol.class, Types.class, boolean.class};
          for (int i = 0; i < 2; i++) {
            for (String lookup : List.of("hasNullableReturn", "hasNonNullReturn")) {
              assertThat(invoke(inherited, lookup, returnParameters, override, types, false))
                  .isEqualTo(false);
              assertThat(invoke(inherited, lookup, returnParameters, override, types, true))
                  .isEqualTo(true);
              assertThat(
                      invoke(
                          inherited,
                          lookup,
                          returnParameters,
                          fixture.method("choose(int)"),
                          types,
                          true))
                  .isEqualTo(false);
            }
            assertThat(lookup(inherited, "nonNullParameters", override))
                .isEqualTo(ImmutableSet.of());
            assertThat(
                    invoke(
                        inherited,
                        "nullImpliesNullParameters",
                        new Class<?>[] {Symbol.MethodSymbol.class, Types.class},
                        override,
                        types))
                .isEqualTo(ImmutableSet.of(0));
          }

          MethodRef basePair =
              methodRef("com.uber.Base", "pair(java.lang.Object,java.lang.Object)");
          MethodRef exactPair =
              methodRef("com.uber.Test", "pair(java.lang.Object,java.lang.Object)");
          LibraryModels models = modelsFor(basePair, exactPair);
          when(models.nullImpliesNullParameters())
              .thenReturn(ImmutableSetMultimap.of(basePair, 1, exactPair, 0));
          Object exact = optimizedModels(models, fixture.state().context);
          for (int i = 0; i < 2; i++) {
            assertThat(
                    invoke(
                        exact,
                        "nullImpliesNullParameters",
                        new Class<?>[] {Symbol.MethodSymbol.class, Types.class},
                        fixture.method(exactPair.fullMethodSig),
                        types))
                .isEqualTo(ImmutableSet.of(0));
          }
        });
  }

  @Test
  public void optimizedViewAndConversionCacheAreSeparatedByCompilerContext() {
    LibraryModelsHandler handler = newHandler(handlerConfig());
    List<Object> views = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      compile(
          fixture -> {
            Object view =
                invoke(
                    handler,
                    "getOptLibraryModels",
                    new Class<?>[] {Context.class},
                    fixture.state().context);
            assertThat(
                    invoke(
                        handler,
                        "getOptLibraryModels",
                        new Class<?>[] {Context.class},
                        fixture.state().context))
                .isSameInstanceAs(view);
            if (!views.isEmpty()) {
              assertThat(view).isNotSameInstanceAs(views.get(0));
            }
            CountingMethodSymbol symbol = new CountingMethodSymbol(fixture.optionalOrElse());
            assertThat(lookup(view, "explicitlyNullableParameters", symbol))
                .isEqualTo(ImmutableSet.of(0));
            assertThat(lookup(view, "explicitlyNullableParameters", symbol))
                .isEqualTo(ImmutableSet.of(0));
            assertThat(symbol.conversions).isEqualTo(1);
            views.add(view);
          });
    }
  }

  @Test
  public void descriptorReuseStillAppliesModelsToEachSuppliedMethodType() {
    compile(
        fixture -> {
          Config config = handlerConfig();
          LibraryModelsHandler handler = newHandler(config);
          CountingMethodSymbol symbol = new CountingMethodSymbol(fixture.optionalOrElse());
          Type declaration = symbol.type;
          Type.MethodType methodType = declaration.asMethodType();
          for (Type returnType :
              List.of(methodType.restype, fixture.state().getSymtab().stringType)) {
            Type.MethodType supplied =
                new Type.MethodType(
                    methodType.argtypes, returnType, methodType.thrown, methodType.tsym);
            Type.MethodType modeled =
                handler.onOverrideMethodType(symbol, supplied, fixture.state(), null);
            assertThat(modeled).isNotSameInstanceAs(supplied);
            assertThat(
                    Nullness.hasNullableAnnotation(
                        modeled.restype.getAnnotationMirrors().stream(), config))
                .isTrue();
            assertThat(
                    Nullness.hasNullableAnnotation(
                        modeled.argtypes.head.getAnnotationMirrors().stream(), config))
                .isTrue();
            assertThat(modeled.restype.tsym).isSameInstanceAs(returnType.tsym);
            assertThat(supplied.restype).isSameInstanceAs(returnType);
            assertThat(symbol.type).isSameInstanceAs(declaration);
          }
          assertThat(symbol.conversions).isEqualTo(1);
        });
  }

  /** Runs each proof once with actual attributed declarations and javac's varargs formatting. */
  private void compile(Consumer<Fixture> assertions) {
    AtomicInteger checked = new AtomicInteger();
    MODEL_ASSERTIONS.set(
        fixture -> {
          assertions.accept(fixture);
          checked.incrementAndGet();
        });
    try {
      CompilationTestHelper.newInstance(ModelAssertions.class, getClass())
          .setArgs(JSpecifyJavacConfig.withJSpecifyModeArgs(List.of()))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import org.jspecify.annotations.Nullable;
              class Base {
                Object choose(Object value) { return value; }
                Object pair(Object first, Object second) { return first; }
              }
              class Test extends Base {
                Test() {}
                @Override Object choose(@Nullable Object value) { return value; }
                Object choose(String value) { return value; }
                Object choose(Object @Nullable ... values) { return values; }
                Object choose(int value) { return value; }
                @Override Object pair(Object first, Object second) { return first; }
                <T> T generic(@Nullable T value) { return value; }
                void unmodeled() {}
                String optional(java.util.Optional<String> value) { return value.orElse(null); }
              }
              """)
          .doTest();
      assertThat(checked.get()).isEqualTo(1);
    } finally {
      MODEL_ASSERTIONS.remove();
    }
  }

  /** Supplies compiler-owned declarations without exposing private production lookup classes. */
  @BugPattern(summary = "Checks library model descriptor caching", severity = SUGGESTION)
  public static final class ModelAssertions extends BugChecker
      implements BugChecker.ClassTreeMatcher {

    /** Runs the proof only on the fixture class, after javac has attributed its methods. */
    @Override
    public Description matchClass(ClassTree tree, VisitorState state) {
      if (tree.getSimpleName().contentEquals("Test")) {
        Objects.requireNonNull(MODEL_ASSERTIONS.get())
            .accept(new Fixture(state, ASTHelpers.getSymbol(tree)));
      }
      return NO_MATCH;
    }
  }

  /** Real symbols and compiler services for the direct cache-boundary proofs. */
  private record Fixture(VisitorState state, Symbol.ClassSymbol owner) {
    /** Finds an overload by its full descriptor, without depending on member iteration order. */
    private Symbol.MethodSymbol method(String signature) {
      return declaredMethod(owner, signature);
    }

    /** Retrieves a built-in model whose parameter and return annotations are both observable. */
    @SuppressWarnings("MemoizeConstantVisitorStateLookups") // one-off compiler fixture lookup
    private Symbol.MethodSymbol optionalOrElse() {
      return declaredMethod(
          (Symbol.ClassSymbol) state.getTypeFromString("java.util.Optional").tsym, "orElse(T)");
    }
  }

  /** Counts descriptor conversions while retaining javac's actual stringification. */
  private static final class CountingMethodSymbol extends Symbol.MethodSymbol {
    private int conversions;

    /** Copies declaration state so mutation tests never modify compiler-owned symbols. */
    private CountingMethodSymbol(Symbol.MethodSymbol declaration) {
      super(declaration.flags(), declaration.name, declaration.type, declaration.owner);
      params = declaration.params;
    }

    /** Records conversion attempts without changing descriptor formatting. */
    @Override
    public String toString() {
      conversions++;
      return super.toString();
    }
  }

  /** Finds a compiler-attributed declaration with precisely the requested signature. */
  private static Symbol.MethodSymbol declaredMethod(Symbol.ClassSymbol owner, String signature) {
    for (Symbol symbol : owner.members().getSymbols()) {
      if (symbol instanceof Symbol.MethodSymbol method
          && MethodRef.fromSymbol(method).fullMethodSig.equals(signature)) {
        return method;
      }
    }
    throw new AssertionError("Missing method " + signature);
  }

  /** Populates every method-indexed table to exercise conversion sharing across all value types. */
  private static LibraryModels modelsFor(MethodRef... refs) {
    LibraryModels models = mock(LibraryModels.class);
    ImmutableSetMultimap.Builder<MethodRef, Integer> params = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<MethodRef, MethodRef> calls = ImmutableSetMultimap.builder();
    ImmutableMap.Builder<MethodRef, ImmutableSetMultimap<Integer, NestedAnnotationInfo>> nested =
        ImmutableMap.builder();
    for (MethodRef ref : refs) {
      params.put(ref, 0);
      calls.put(ref, ref);
      nested.put(ref, ImmutableSetMultimap.of());
    }
    ImmutableSetMultimap<MethodRef, Integer> parameterModels = params.build();
    when(models.failIfNullParameters()).thenReturn(parameterModels);
    when(models.explicitlyNullableParameters()).thenReturn(parameterModels);
    when(models.nonNullParameters()).thenReturn(parameterModels);
    when(models.nullImpliesTrueParameters()).thenReturn(parameterModels);
    when(models.nullImpliesFalseParameters()).thenReturn(parameterModels);
    when(models.ensuresNonNullIfTrueMethodCalls()).thenReturn(calls.build());
    when(models.nullImpliesNullParameters()).thenReturn(parameterModels);
    when(models.nullableReturns()).thenReturn(ImmutableSet.copyOf(refs));
    when(models.nonNullReturns()).thenReturn(ImmutableSet.copyOf(refs));
    when(models.castToNonNullMethods()).thenReturn(parameterModels);
    when(models.methodTypeVariablesWithNullableUpperBounds()).thenReturn(parameterModels);
    when(models.nestedAnnotationsForMethods()).thenReturn(nested.buildOrThrow());
    return models;
  }

  /** Builds the private optimized view without widening its production visibility for tests. */
  private static Object optimizedModels(LibraryModels models, Context context) {
    try {
      for (Class<?> nested : LibraryModelsHandler.class.getDeclaredClasses()) {
        if (nested.getSimpleName().equals("OptimizedLibraryModels")) {
          Constructor<?> constructor =
              nested.getDeclaredConstructor(LibraryModels.class, Context.class);
          constructor.setAccessible(true);
          return constructor.newInstance(models, context);
        }
      }
      throw new AssertionError("Missing optimized library model view");
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not construct optimized library models", exception);
    }
  }

  /** Asserts matching or negative lookup through every name-indexed table in the same view. */
  private static void assertAllTables(Object view, Symbol.MethodSymbol symbol, boolean matches) {
    int tables = 0;
    try {
      for (Field field : view.getClass().getDeclaredFields()) {
        if (field.getType().getSimpleName().equals("NameIndexedMap")) {
          field.setAccessible(true);
          Object result = lookup(field.get(view), "get", symbol);
          if (matches) {
            assertThat(result).isNotNull();
          } else {
            assertThat(result).isNull();
          }
          tables++;
        }
      }
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not inspect model lookup tables", exception);
    }
    assertThat(tables).isEqualTo(12);
  }

  /** Looks up a symbol through a private production table or optimized-view accessor. */
  private static Object lookup(Object owner, String name, Symbol.MethodSymbol symbol) {
    return invoke(owner, name, new Class<?>[] {Symbol.MethodSymbol.class}, symbol);
  }

  /** Invokes a private stage while preserving assertion failures from the tested method. */
  private static Object invoke(
      Object owner, String name, Class<?>[] parameters, Object... arguments) {
    try {
      Method method = owner.getClass().getDeclaredMethod(name, parameters);
      method.setAccessible(true);
      return method.invoke(owner, arguments);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new LinkageError("Could not execute model lookup " + name, exception);
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not invoke model lookup " + name, exception);
    }
  }

  /** Creates an actual handler with the built-in JSpecify library models enabled. */
  private static LibraryModelsHandler newHandler(Config config) {
    LibraryModelsHandler handler = new LibraryModelsHandler(config);
    handler.initMainHandler(new Handler() {});
    return handler;
  }

  /** Returns the configuration used for the built-in model application proof. */
  private static Config handlerConfig() {
    return new NullAway(
            ErrorProneFlags.fromMap(
                Map.of(
                    "NullAway:AnnotatedPackages", "com.uber",
                    "NullAway:JSpecifyMode", "true",
                    "NullAway:JSpecifyExperimental", "true")))
        .getConfig();
  }

  /** Creates a temporary JAR containing {@link #RESOURCE_NAME} with the supplied contents. */
  private File createJarContainingResource(byte[] contents) throws IOException {
    File jarFile = temporaryFolder.newFile("models.jar");
    try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jarFile))) {
      output.putNextEntry(new ZipEntry(RESOURCE_NAME));
      output.write(contents);
      output.closeEntry();
    }
    return jarFile;
  }
}
