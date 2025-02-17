package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.internal.concurrent.CompositeStoppable;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import java.io.Closeable;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Cleans up resources (e.g. file handles) after compilation has finished.
 */
class ResourceCleaningCompilationTask implements JavaCompiler.CompilationTask {
    private final JavaCompiler.CompilationTask delegate;
    private final Closeable fileManager;

    ResourceCleaningCompilationTask(JavaCompiler.CompilationTask delegate, Closeable fileManager) {
        this.delegate = delegate;
        this.fileManager = fileManager;
    }

    @Override
    public void addModules(Iterable<String> moduleNames) {
    }

    @Override
    public void setProcessors(Iterable<? extends Processor> processors) {
        delegate.setProcessors(processors);
    }

    @Override
    public void setLocale(Locale locale) {
        delegate.setLocale(locale);
    }

    @Override
    public Boolean call() {
        try {
            return delegate.call();
        } finally {
            CompositeStoppable.stoppable(fileManager).stop();
            cleanupZipCache();
        }
    }

    /**
     * The javac file manager uses a shared ZIP cache which keeps file handles open
     * after compilation. It's supposed to be tunable with the -XDuseOptimizedZip parameter,
     * but the {@link JavaCompiler#getStandardFileManager(DiagnosticListener, Locale, Charset)}
     * method does not take arguments, so the cache can't be turned off.
     * So instead we clean it ourselves using reflection.
     */
    private void cleanupZipCache() {
        try {
            Class<?> zipFileIndexCache = Class.forName("com.sun.tools.javac.file.ZipFileIndexCache");
            Object instance = zipFileIndexCache.getMethod("getSharedInstance").invoke(null);
            zipFileIndexCache.getMethod("clearCache").invoke(instance);
        } catch (Throwable e) {
            // Not an OpenJDK-compatible compiler or signature changed
        }
    }
}
