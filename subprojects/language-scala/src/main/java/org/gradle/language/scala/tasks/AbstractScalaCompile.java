/*
 * Copyright 2014 the original author or authors.
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
 * limitations under the License.
 */

package org.gradle.language.scala.tasks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.gradle.api.Incubating;
import org.gradle.api.JavaVersion;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.tasks.compile.CompilerForkUtils;
import org.gradle.api.internal.tasks.compile.processing.AnnotationProcessorPathFactory;
import org.gradle.api.internal.tasks.scala.CleaningScalaCompiler;
import org.gradle.api.internal.tasks.scala.DefaultScalaJavaJointCompileSpec;
import org.gradle.api.internal.tasks.scala.DefaultScalaJavaJointCompileSpecFactory;
import org.gradle.api.internal.tasks.scala.ScalaCompileSpec;
import org.gradle.api.internal.tasks.scala.ScalaJavaJointCompileSpec;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.scala.IncrementalCompileOptions;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * An abstract Scala compile task sharing common functionality for compiling scala.
 */
@Incubating
public abstract class AbstractScalaCompile extends AbstractCompile {
    protected static final Logger LOGGER = Logging.getLogger(AbstractScalaCompile.class);
    private final BaseScalaCompileOptions scalaCompileOptions;
    private final CompileOptions compileOptions;
    private final RegularFileProperty analysisMappingFile = newOutputFile();
    private final ConfigurableFileCollection analysisFiles = getProject().files();

    protected AbstractScalaCompile(BaseScalaCompileOptions scalaCompileOptions) {
        ObjectFactory objectFactory = getServices().get(ObjectFactory.class);
        this.compileOptions = objectFactory.newInstance(CompileOptions.class);
        this.scalaCompileOptions = scalaCompileOptions;
        this.scalaCompileOptions.setIncrementalOptions(objectFactory.newInstance(IncrementalCompileOptions.class));

        CompilerForkUtils.doNotCacheIfForkingViaExecutable(compileOptions, getOutputs());
    }

    /**
     * Returns the Scala compilation options.
     */
    @Nested
    public BaseScalaCompileOptions getScalaCompileOptions() {
        return scalaCompileOptions;
    }

    /**
     * Returns the Java compilation options.
     */
    @Nested
    public CompileOptions getOptions() {
        return compileOptions;
    }

    abstract protected org.gradle.language.base.internal.compile.Compiler<ScalaJavaJointCompileSpec> getCompiler(ScalaJavaJointCompileSpec spec);

    @Override
    @TaskAction
    protected void compile() {
        ScalaJavaJointCompileSpec spec = createSpec();
        configureIncrementalCompilation(spec);
        Compiler<ScalaJavaJointCompileSpec> compiler = getCompiler(spec);
        if (isNonIncrementalCompilation()) {
            compiler = new CleaningScalaCompiler(compiler, getOutputs());
        }
        compiler.execute(spec);
    }

    private boolean isNonIncrementalCompilation() {
        File analysisFile = getScalaCompileOptions().getIncrementalOptions().getAnalysisFile().getAsFile().get();
        if (!analysisFile.exists()) {
            LOGGER.info("Zinc is doing a full recompile since the analysis file doesn't exist");
            return true;
        }
        return false;
    }

    protected ScalaJavaJointCompileSpec createSpec() {
        DefaultScalaJavaJointCompileSpec spec = new DefaultScalaJavaJointCompileSpecFactory(compileOptions).create();
        spec.setSourceFiles(getSource().getFiles());
        spec.setDestinationDir(getDestinationDir());
        spec.setWorkingDir(getProject().getProjectDir());
        spec.setTempDir(getTemporaryDir());
        spec.setCompileClasspath(ImmutableList.copyOf(getClasspath()));
        spec.setSourceCompatibility(getSourceCompatibility());
        spec.setTargetCompatibility(getTargetCompatibility());
        spec.setCompileOptions(getOptions());
        spec.setScalaCompileOptions(scalaCompileOptions);
        spec.setAnnotationProcessorPath(ImmutableList.copyOf(getEffectiveAnnotationProcessorPath()));
        return spec;
    }

    protected void configureIncrementalCompilation(ScalaCompileSpec spec) {
        IncrementalCompileOptions incrementalOptions = scalaCompileOptions.getIncrementalOptions();

        File analysisFile = incrementalOptions.getAnalysisFile().getAsFile().get();
        Map<File, File> globalAnalysisMap = getAnalysisMappingsForOtherProjects();
        spec.setAnalysisMap(globalAnalysisMap);
        spec.setAnalysisFile(analysisFile);

        // If this Scala compile is published into a jar, generate a analysis mapping file
        if (incrementalOptions.getPublishedCode().isPresent()) {
            File publishedCode = incrementalOptions.getPublishedCode().getAsFile().get();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("scala-incremental Analysis file: {}", analysisFile);
                LOGGER.debug("scala-incremental Published code: {}", publishedCode);
            }
            File analysisMapping = getAnalysisMappingFile().getAsFile().get();
            GFileUtils.writeFile(publishedCode.getAbsolutePath() + "\n" + analysisFile.getAbsolutePath(), analysisMapping);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("scala-incremental Analysis map: {}", globalAnalysisMap);
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<File, File> getAnalysisMappingsForOtherProjects() {
        Map<File, File> analysisMap = Maps.newHashMap();
        for (File mapping : analysisFiles.getFiles()) {
            if (mapping.exists()) {
                try {
                    List<String> lines = Files.readLines(mapping, Charset.defaultCharset());
                    assert lines.size() == 2;
                    analysisMap.put(new File(lines.get(0)), new File(lines.get(1)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return analysisMap;
    }

    /**
     * Returns the path to use for annotation processor discovery. Returns an empty collection when no processing should be performed, for example when no annotation processors are present in the compile classpath or annotation processing has been disabled.
     *
     * <p>You can specify this path using {@link CompileOptions#setAnnotationProcessorPath(FileCollection)} or {@link CompileOptions#setCompilerArgs(java.util.List)}. When not explicitly set using one of the methods on {@link CompileOptions}, the compile classpath will be used when there are annotation processors present in the compile classpath. Otherwise this path will be empty.
     *
     * <p>This path is always empty when annotation processing is disabled.</p>
     *
     * @since 4.1
     */
    @Incubating
    @Classpath
    public FileCollection getEffectiveAnnotationProcessorPath() {
        AnnotationProcessorPathFactory annotationProcessorPathFactory = getServices().get(AnnotationProcessorPathFactory.class);
        return annotationProcessorPathFactory.getEffectiveAnnotationProcessorClasspath(compileOptions, getClasspath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public FileTree getSource() {
        return super.getSource();
    }

    /**
     * The Java major version of the JVM the Scala compiler is running on.
     *
     * @since 4.6
     */
    @Incubating
    @Input
    // We track this as an input since the Scala compiler output may depend on it.
    // TODO: This should be replaced by a property in the Scala toolchain as soon as we model these.
    protected String getJvmVersion() {
        return JavaVersion.current().getMajorVersion();
    }

    /**
     * TODO
     * @return
     */
    public ConfigurableFileCollection getAnalysisFiles() {
        return analysisFiles;
    }

    @LocalState
    public RegularFileProperty getAnalysisMappingFile() {
        return analysisMappingFile;
    }
}
