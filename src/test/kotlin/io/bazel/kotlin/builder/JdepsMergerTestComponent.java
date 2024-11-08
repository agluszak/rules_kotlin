package io.bazel.kotlin.builder;

import dagger.Component;
import io.bazel.kotlin.builder.tasks.jvm.JdepsMerger;
import javax.inject.Singleton;

@Singleton
public interface JdepsMergerTestComponent {

  JdepsMerger jdepsMerger();

  @Component.Builder
  interface Builder {

    JdepsMergerTestComponent build();
  }
}
