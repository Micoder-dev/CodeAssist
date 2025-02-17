package com.tyron.builder.initialization;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.initialization.ProjectDescriptor;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.util.Path;
import com.tyron.builder.configuration.project.BuiltInCommand;
import com.tyron.builder.initialization.buildsrc.BuildSrcDetector;
import com.tyron.builder.initialization.layout.BuildLayout;
import com.tyron.builder.initialization.layout.BuildLayoutConfiguration;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;

import java.util.List;

import java.io.File;
import java.util.List;

/**
 * Handles locating and processing setting.gradle files.  Also deals with the buildSrc module, since that modules is
 * found after settings is located, but needs to be built before settings is processed.
 */
public class DefaultSettingsLoader implements SettingsLoader {
    public static final String BUILD_SRC_PROJECT_PATH = ":" + SettingsInternal.BUILD_SRC;
    private final SettingsProcessor settingsProcessor;
    private final BuildLayoutFactory buildLayoutFactory;
    private final List<BuiltInCommand> builtInCommands;

    public DefaultSettingsLoader(
            SettingsProcessor settingsProcessor,
            BuildLayoutFactory buildLayoutFactory,
            List<BuiltInCommand> builtInCommands
    ) {
        this.settingsProcessor = settingsProcessor;
        this.buildLayoutFactory = buildLayoutFactory;
        this.builtInCommands = builtInCommands;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        StartParameter startParameter = gradle.getStartParameter();

        SettingsLocation settingsLocation = buildLayoutFactory.getLayoutFor(new BuildLayoutConfiguration(startParameter));

        SettingsInternal settings = findSettingsAndLoadIfAppropriate(gradle, startParameter, settingsLocation, gradle.getClassLoaderScope());
        ProjectSpec spec = ProjectSpecs.forStartParameter(startParameter, settings);
        if (useEmptySettings(spec, settings, startParameter)) {
            settings = createEmptySettings(gradle, startParameter, settings.getClassLoaderScope());
        }

        setDefaultProject(spec, settings);
        return settings;
    }

    private boolean useEmptySettings(ProjectSpec spec, SettingsInternal loadedSettings, StartParameter startParameter) {
        // Never use empty settings when the settings were explicitly set
        @SuppressWarnings("deprecation")
        File customSettingsFile = startParameter.getSettingsFile();
        if (customSettingsFile != null) {
            return false;
        }

        // Use the loaded settings if it includes the target project (based on build file, project dir or current dir)
        if (spec.containsProject(loadedSettings.getProjectRegistry())) {
            return false;
        }

        // Allow a built-in command to run in a directory not contained in the settings file (but don't use the settings from that file)
        for (BuiltInCommand command : builtInCommands) {
            if (command.commandLineMatches(startParameter.getTaskNames())) {
                // Allow built-in command to run in a directory not contained in the settings file (but don't use the settings from that file)
                return true;
            }
        }

        // Allow a buildSrc directory to have no settings file
        if (startParameter.getProjectDir() != null && startParameter.getProjectDir().getName().equals(SettingsInternal.BUILD_SRC) && BuildSrcDetector
                .isValidBuildSrcBuild(startParameter.getProjectDir())) {
            return true;
        }

        // Use an empty settings for a target build file located in the same directory as the settings file.
        return startParameter.getProjectDir() != null && loadedSettings.getSettingsDir().equals(startParameter.getProjectDir());
    }

    @SuppressWarnings("deprecation") // StartParameter.setSettingsFile() and StartParameter.getBuildFile()
    private SettingsInternal createEmptySettings(GradleInternal gradle, StartParameter startParameter, ClassLoaderScope classLoaderScope) {
        StartParameterInternal noSearchParameter = (StartParameterInternal) startParameter.newInstance();
        noSearchParameter.setSettingsFile(null);
        noSearchParameter.useEmptySettings();
        noSearchParameter.doNotSearchUpwards();
        BuildLayout layout = buildLayoutFactory.getLayoutFor(new BuildLayoutConfiguration(noSearchParameter));
        SettingsInternal settings = findSettingsAndLoadIfAppropriate(gradle, noSearchParameter, layout, classLoaderScope);

        // Set explicit build file, if required
        @SuppressWarnings("deprecation")
        File customBuildFile = noSearchParameter.getBuildFile();
        if (customBuildFile != null) {
            ProjectDescriptor rootProject = settings.getRootProject();
            rootProject.setBuildFileName(noSearchParameter.getBuildFile().getName());
        }
        return settings;
    }

    private void setDefaultProject(ProjectSpec spec, SettingsInternal settings) {
        //settings.getSettingsScript().getDisplayName()
        settings.setDefaultProject(spec.selectProject("settings file", settings.getProjectRegistry()));
    }

    /**
     * Finds the settings.gradle for the given startParameter, and loads it if contains the project selected by the
     * startParameter, or if the startParameter explicitly specifies a settings script.  If the settings file is not
     * loaded (executed), then a null is returned.
     */
    private SettingsInternal findSettingsAndLoadIfAppropriate(
            GradleInternal gradle,
            StartParameter startParameter,
            SettingsLocation settingsLocation,
            ClassLoaderScope classLoaderScope
    ) {
        SettingsInternal settings = settingsProcessor.process(gradle, settingsLocation, classLoaderScope, startParameter);
        validate(settings);
        return settings;
    }

    private void validate(SettingsInternal settings) {
        settings.getProjectRegistry().getAllProjects().forEach(project -> {
            if (project.getPath().equals(BUILD_SRC_PROJECT_PATH)) {
                Path buildPath = settings.getGradle().getIdentityPath();
                String suffix = buildPath == Path.ROOT ? "" : " (in build " + buildPath + ")";
                throw new BuildException("'" + SettingsInternal.BUILD_SRC + "' cannot be used as a project name as it is a reserved name" + suffix);
            }
        });
    }
}

