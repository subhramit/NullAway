# Fresh review reproducers

Temporary JUnit probes used for the review; these are not part of the proposed implementation patches.

```java
  @Test
  public void reviewArrayDeclarationBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Pair<A extends @Nullable Object, B extends @Nullable Object>
                  extends Box<B> {}
              static class Parent<X extends @Nullable Object> {
                <T extends X> T id(T value) { return value; }
              }
              static void test(Parent<Box<String>[]> parent, Pair<Integer, @Nullable String>[] value) {
                // BUG: Diagnostic contains: incompatible types
                parent.id(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reviewRecursiveDeclarationBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Node<A extends @Nullable Object, B extends @Nullable Object> {}
              static class Box<E extends @Nullable Object> {}
              static class Bad extends Node<Bad, Box<@Nullable String>> {}
              static <T extends Node<T, Box<String>>> void require(T value) {}
              static void test(Bad bad) {
                // BUG: Diagnostic contains: incompatible types
                require(bad);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reviewStructuredMethodReference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              interface Fn<I extends @Nullable Object, O extends @Nullable Object> {
                O apply(I value);
              }
              static <T extends @Nullable Object> T id(T value) { return value; }
              static <I extends @Nullable Object> void use(I value, Fn<I, Box<String>> fn) {}
              static void test(Box<@Nullable String> value) {
                // BUG: Diagnostic contains: mismatched type parameter nullability
                use(value, Test::id);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reviewWildcardDeclarationBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Bad extends Box<Box<@Nullable String>> {}
              static <T extends Box<? extends Box<String>>> void require(T value) {}
              static void test(Bad bad) {
                // BUG: Diagnostic contains: incompatible types
                require(bad);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reviewIndependentNestedBoundViolations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              static <T extends Box<String>> T boundedId(T value) { return value; }
              static <A extends @Nullable Object, B extends @Nullable Object> void pair(
                  A first, B second) {}
              static void test(Bad firstBad, Bad secondBad) {
                pair(
                    // BUG: Diagnostic contains: incompatible types
                    boundedId(firstBad),
                    // BUG: Diagnostic contains: incompatible types
                    boundedId(secondBad));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reviewUpperOnlyBoundConflict() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <T extends Box<String>> T make() { throw new AssertionError(); }
              // BUG: Diagnostic contains: incompatible types
              Box<@Nullable String> field = make();
            }
            """)
        .doTest();
  }


```
