package io.bazel.kotlin.builder.tasks.jvm;

import io.bazel.kotlin.builder.KotlinJvmTestBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;

@RunWith(JUnit4.class)
public class PassthroughFlagsTest {
    private static final KotlinJvmTestBuilder ctx = new KotlinJvmTestBuilder();

    @Test
    public void testPassthroughFlagsAreRespected() {
        ctx.runFailingCompileTaskAndValidateOutput(
            () ->
                ctx.runCompileTask(
                    c -> {
                        c.compileKotlin();
                        c.addSource("AClass.kt", "package something; class AClass{}");
                        c.outputJar();
                        // This flag should cause compilation failure as it is invalid
                        c.addPassthroughFlag("-non-existent-flag-that-fails-build");
                    }),
            lines -> assertThat(lines.stream().anyMatch(line -> line.contains("Invalid argument: -non-existent-flag-that-fails-build"))).isTrue()
        );
    }
}
