package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.service.scopes.Scope;
import com.tyron.builder.api.internal.service.scopes.Scopes;

/**
 * Can be implemented by plugins to provide services in various scopes.
 *
 * <p>Implementations are discovered using the JAR service locator mechanism (see {@link org.gradle.internal.service.ServiceLocator}).
 */
public interface PluginServiceRegistry {
    /**
     * Called once per process, to register any globally scoped services. These services are reused across builds in the same process.
     * The services are closed when the process finishes.
     *
     * <p>Global services are visible to all other services.</p>
     *
     * @see Scope.Global
     */
    void registerGlobalServices(ServiceRegistration registration);

    /**
     * Called to register any services scoped to the Gradle user home directory. These services are reused across builds in the same process while the Gradle user home directory remains unchanged. The services are closed when the Gradle user home directory changes.
     *
     * <p>These services are "mostly global" as there is usually only a single Gradle user home directory used for a given process. Some processes, such as test processes, may run builds with different user home directories.</p>
     *
     * <p>Global services are visible to these shared services, but not vice versa.</p>
     *
     * @see Scopes.UserHome
     */
    void registerGradleUserHomeServices(ServiceRegistration registration);

    /**
     * Called once per build session to register any build session scoped services.  These services are reused across build invocations when in
     * continuous mode. They are closed at the end of the build session.
     *
     * <p>Global and shared services are visible to build session scope services, but not vice versa</p>
     *
     * @see Scopes.BuildSession
     */
    void registerBuildSessionServices(ServiceRegistration registration);

    /**
     * Called once per build invocation on a build tree to register any build tree scoped services to use during that build invocation.  These services are recreated when in continuous mode and shared across all nested builds. They are closed when the build invocation is completed.
     *
     * <p>Global, user home and build session services are visible to build tree scope services, but not vice versa.</p>
     *
     * @see Scopes.BuildTree
     */
    void registerBuildTreeServices(ServiceRegistration registration);

    /**
     * Called once per build invocation on a build, to register any build scoped services to use during that build invocation. These services are closed at the end of the build invocation.
     *
     * <p>Global, user home, build session and build tree services are visible to the build scope services, but not vice versa.</p>
     *
     * @see Scopes.Build
     */
    void registerBuildServices(ServiceRegistration registration);

    /**
     * Called once per build invocation on a build, to register any {@link org.gradle.api.initialization.Settings} scoped services. These services are closed at the end of the build invocation.
     *
     * <p>Global, user home, build session, build tree and build scoped services are visible to the settings scope services, but not vice versa.</p>
     */
    void registerSettingsServices(ServiceRegistration registration);

    /**
     * Called once per build invocation on a build, to register any {@link org.gradle.api.invocation.Gradle} scoped services. These services are closed at the end of the build invocation.
     *
     * <p>Global, user home, build session, build tree and and build scoped services are visible to the gradle scope services, but not vice versa.</p>
     *
     * @see Scopes.Gradle
     */
    void registerGradleServices(ServiceRegistration registration);

    /**
     * Called once per project per build invocation, to register any project scoped services. These services are closed at the end of the build invocation.
     *
     * <p>Global, user home, build session, build tree, build and gradle scoped services are visible to the project scope services, but not vice versa.</p>
     *
     * @see Scopes.Project
     */
    void registerProjectServices(ServiceRegistration registration);

}
