package com.tyron.builder.api.tasks;

import com.tyron.builder.api.project.BuildProject;

import java.lang.annotation.Annotation;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property as specifying one or more output directories for a task.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>This will cause the task to be considered out-of-date when the directory paths or task
 * output to those directories have been modified since the task was last run.</p>
 *
 * <p>When the annotated property is a {@link java.util.Map}, the keys of the map must be non-empty strings.
 * The values of the map will be evaluated to individual directories as per
 * {@link BuildProject#file(Object)}.</p>
 *
 * <p>
 * Otherwise the given directories will be evaluated as per {@link BuildProject#files(Object...)}.
 * Task output caching will be disabled if the outputs contain file trees.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface OutputDirectories {
}