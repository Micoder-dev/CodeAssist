package com.tyron.builder.api.internal.file;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.file.DirectoryTree;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.MutableBoolean;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.FileBackedDirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.FileSystemMirroringFileTree;
import com.tyron.builder.api.internal.logging.TreeFormatter;
import com.tyron.builder.api.internal.provider.BuildableBackedSetProvider;
import com.tyron.builder.api.internal.tasks.AbstractTaskDependency;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.util.CollectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class AbstractFileCollection implements FileCollectionInternal {
    protected final Factory<PatternSet> patternSetFactory;

    protected AbstractFileCollection(Factory<PatternSet> patternSetFactory) {
        this.patternSetFactory = patternSetFactory;
    }

    @SuppressWarnings("deprecation")
    public AbstractFileCollection() {
        this.patternSetFactory = PatternSets.getNonCachingPatternSetFactory();
    }

    /**
     * Returns the display name of this file collection. Used in log and error messages.
     *
     * @return the display name
     */
    public abstract String getDisplayName();

    @Override
    public String toString() {
        return getDisplayName();
    }

    /**
     * This is final - override {@link #appendContents(TreeFormatter)}  instead to add type specific content.
     */
    @Override
    public final TreeFormatter describeContents(TreeFormatter formatter) {
        formatter.node("collection type: ").appendType(getClass()).append(" (id: ").append(String.valueOf(System.identityHashCode(this))).append(")");
        formatter.startChildren();
        appendContents(formatter);
        formatter.endChildren();
        return formatter;
    }

    protected void appendContents(TreeFormatter formatter) {
    }

    // This is final - override {@link TaskDependencyContainer#visitDependencies} to provide the dependencies instead.
    @Override
    public final TaskDependency getBuildDependencies() {
        assertCanCarryBuildDependencies();
        return new AbstractTaskDependency() {
            @Override
            public String toString() {
                return "Dependencies of " + getDisplayName();
            }

            @Override
            public void visitDependencies(TaskDependencyResolveContext context) {
                context.add(AbstractFileCollection.this);
            }
        };
    }

    protected void assertCanCarryBuildDependencies() {
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        // Assume no dependencies
    }

    @Override
    public FileCollectionInternal replace(FileCollectionInternal original, Supplier<FileCollectionInternal> supplier) {
        if (original == this) {
            return supplier.get();
        }
        return this;
    }

    @Override
    public Set<File> getFiles() {
        // Use a JVM type here, rather than a Guava type, as some plugins serialize this return value and cannot deserialize the result
        Set<File> files = new LinkedHashSet<>();
        visitContents(new FileCollectionStructureVisitor() {
            @Override
            public void visitCollection(Source source, Iterable<File> contents) {
                for (File content : contents) {
                    files.add(content);
                }
            }

            private void addTreeContents(FileTreeInternal fileTree) {
                // TODO - add some convenient way to visit the files of the tree without collecting them into a set
                files.addAll(fileTree.getFiles());
            }

            @Override
            public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                addTreeContents(fileTree);
            }

            @Override
            public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
                addTreeContents(fileTree);
            }

            @Override
            public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                addTreeContents(fileTree);
            }
        });
        return files;
    }

    @Override
    public File getSingleFile() throws IllegalStateException {
        Iterator<File> iterator = iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException(String.format("Expected %s to contain exactly one file, however, it contains no files.", getDisplayName()));
        }
        File singleFile = iterator.next();
        if (iterator.hasNext()) {
            throw new IllegalStateException(String.format("Expected %s to contain exactly one file, however, it contains more than one file.", getDisplayName()));
        }
        return singleFile;
    }

    @Override
    public Iterator<File> iterator() {
        return getFiles().iterator();
    }

    @Override
    public String getAsPath() {
        showGetAsPathDeprecationWarning();
        return CollectionUtils.join(File.pathSeparator, this);
    }

    private void showGetAsPathDeprecationWarning() {
        List<String> filesAsPaths = this.getFiles().stream()
                .map(File::getPath)
                .filter(path -> path.contains(File.pathSeparator))
                .collect(Collectors.toList());
        if (!filesAsPaths.isEmpty()) {
            String displayedFilePaths = filesAsPaths.stream().map(path -> "'" + path + "'").collect(Collectors.joining(","));
//            LOGGE.deprecateBehaviour(String.format(
//                    "Converting files to a classpath string when their paths contain the path separator '%s' has been deprecated." +
//                    " The path separator is not a valid element of a file path. Problematic paths in '%s' are: %s.",
//                    File.pathSeparator,
//                    getDisplayName(),
//                    displayedFilePaths
//            ))
//                    .withAdvice("Add the individual files to the file collection instead.")
//                    .willBecomeAnErrorInGradle8()
//                    .withUpgradeGuideSection(7, "file_collection_to_classpath")
//                    .nagUser();
        }
    }

    @Override
    public boolean contains(File file) {
        return getFiles().contains(file);
    }

    @Override
    public FileCollection plus(FileCollection collection) {
        return new UnionFileCollection(this, (FileCollectionInternal) collection);
    }

    @Override
    public Provider<Set<FileSystemLocation>> getElements() {
        return new BuildableBackedSetProvider<>(
                this,
                new FileCollectionElementsFactory(this)
        );
    }

    private static class FileCollectionElementsFactory implements Factory<Set<FileSystemLocation>> {

        private final FileCollection fileCollection;

        private FileCollectionElementsFactory(FileCollection fileCollection) {
            this.fileCollection = fileCollection;
        }

        @Override
        public Set<FileSystemLocation> create() {
            // TODO - visit the contents of this collection instead.
            // This is just a super simple implementation for now
            Set<File> files = fileCollection.getFiles();
            ImmutableSet.Builder<FileSystemLocation> builder = ImmutableSet
                    .builderWithExpectedSize(files.size());
            for (File file : files) {
                builder.add(new DefaultFileSystemLocation(file));
            }
            return builder.build();
        }
    }

    @Override
    public FileCollection minus(final FileCollection collection) {
        return new SubtractingFileCollection(this, collection);
    }

//    @Override
//    public void addToAntBuilder(Object builder, String nodeName, AntType type) {
//        if (type == AntType.ResourceCollection) {
//            addAsResourceCollection(builder, nodeName);
//        } else if (type == AntType.FileSet) {
//            addAsFileSet(builder, nodeName);
//        } else {
//            addAsMatchingTask(builder, nodeName);
//        }
//    }

//    protected void addAsMatchingTask(Object builder, String nodeName) {
//        new AntFileCollectionMatchingTaskBuilder(getAsFileTrees()).addToAntBuilder(builder, nodeName);
//    }
//
//    protected void addAsFileSet(Object builder, String nodeName) {
//        new AntFileSetBuilder(getAsFileTrees()).addToAntBuilder(builder, nodeName);
//    }
//
//    protected void addAsResourceCollection(Object builder, String nodeName) {
//        new AntFileCollectionBuilder(this).addToAntBuilder(builder, nodeName);
//    }

    /**
     * Returns this collection as a set of {@link DirectoryFileTree} instance. These are used to map to Ant types.
     */
    protected Collection<DirectoryTree> getAsFileTrees() {
        List<DirectoryTree> fileTrees = new ArrayList<>();
        visitStructure(new FileCollectionStructureVisitor() {
            @Override
            public void visitCollection(Source source, Iterable<File> contents) {
                for (File file : contents) {
                    if (file.isFile()) {
                        fileTrees.add(new FileBackedDirectoryFileTree(file));
                    }
                }
            }

            @Override
            public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
                if (root.isFile()) {
                    fileTrees.add(new FileBackedDirectoryFileTree(root));
                } else if (root.isDirectory()) {
                    fileTrees.add(new DirectoryTree() {
                        @Override
                        public File getDir() {
                            return root;
                        }

                        @Override
                        public PatternSet getPatterns() {
                            return patterns;
                        }
                    });
                }
            }

            @Override
            public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                // Visit the contents of the tree to generate the tree
                if (visitAll(sourceTree)) {
                    fileTrees.add(sourceTree.getMirror());
                }
            }

            @Override
            public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
                visitGenericFileTree(fileTree, sourceTree);
            }
        });
        return fileTrees;
    }

    /**
     * Visits all the files of the given tree.
     */
    protected static boolean visitAll(FileSystemMirroringFileTree tree) {
        final MutableBoolean hasContent = new MutableBoolean();
        tree.visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails dirDetails) {
                dirDetails.getFile();
                hasContent.set(true);
            }

            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                fileDetails.getFile();
                hasContent.set(true);
            }
        });
        return hasContent.get();
    }

//    @Override
//    public Object addToAntBuilder(Object node, String childNodeName) {
//        addToAntBuilder(node, childNodeName, AntType.ResourceCollection);
//        return this;
//    }

    @Override
    public boolean isEmpty() {
        return getFiles().isEmpty();
    }

    @Override
    public FileTreeInternal getAsFileTree() {
        return new FileCollectionBackedFileTree(patternSetFactory, this);
    }

    @Override
    public FileCollectionInternal filter(final Predicate<? super File> filterSpec) {
        return new FilteredFileCollection(this, filterSpec);
    }

    /**
     * This is final - override {@link #visitContents(FileCollectionStructureVisitor)} instead to provide the contents.
     */
    @Override
    public final void visitStructure(FileCollectionStructureVisitor visitor) {
        if (visitor.startVisit(OTHER, this)) {
            visitContents(visitor);
        }
    }

    protected void visitContents(FileCollectionStructureVisitor visitor) {
        visitor.visitCollection(OTHER, this);
    }
}