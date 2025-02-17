package com.tyron.builder.api.internal.operations;

/**
 * Classifies a build operation such that executors and event listeners can
 * react differently depending on this type.
 *
 * @since 4.0
 */
public enum BuildOperationCategory implements BuildOperationMetadata {
    /**
     * Configure the root build. May also include nested {@link #CONFIGURE_BUILD} and {@link #RUN_WORK} operations.
     */
    CONFIGURE_ROOT_BUILD(false, false, false),

    /**
     * Configure a nested build or a buildSrc build.
     */
    CONFIGURE_BUILD(false, false, false),

    /**
     * Configure a single project in any build.
     */
    CONFIGURE_PROJECT(true, false, false),

    /**
     * Execute the main tasks of a build tree. Also known as the "execution phase".
     */
    RUN_MAIN_TASKS(false, false, false),

    /**
     * Execute all work in a particular build in the tree. Includes {@link #TASK} and Includes {@link #TRANSFORM} operations.
     */
    RUN_WORK(false, false, false),

    /**
     * Execute an individual task.
     */
    TASK(true, true, true),

    /**
     * Execute an individual transform.
     */
    TRANSFORM(true, true, false),

    /**
     * Operation doesn't belong to any category.
     */
    UNCATEGORIZED(false, false, false);

    private final boolean grouped;
    private final boolean topLevelWorkItem;
    private final boolean showHeader;

    BuildOperationCategory(boolean grouped, boolean topLevelWorkItem, boolean showHeader) {
        this.grouped = grouped;
        this.topLevelWorkItem = topLevelWorkItem;
        this.showHeader = showHeader;
    }

    public boolean isGrouped() {
        return grouped;
    }

    public boolean isTopLevelWorkItem() {
        return topLevelWorkItem;
    }

    public boolean isShowHeader() {
        return showHeader;
    }
}