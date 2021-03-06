/*
 * Copyright 2015-2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.wildfly.swarm.bootstrap.env.FractionManifest;
import org.wildfly.swarm.bootstrap.env.WildFlySwarmManifest;
import org.wildfly.swarm.fractions.FractionDescriptor;
import org.wildfly.swarm.jdk.specific.JarFiles;
import org.wildfly.swarm.tools.utils.ChecksumUtil;

import static java.util.Arrays.asList;

/**
 * @author Bob McWhirter
 * @author Ken Finnigan
 * @author Heiko Braun
 */
public class DependencyManager implements ResolvedDependencies {

    public DependencyManager(ArtifactResolver resolver, boolean removeAllThorntailLibs) {
        this.resolver = resolver;
        this.removeAllThorntailLibs = removeAllThorntailLibs;
    }

    public void addAdditionalModule(Path module) {
        try {
            analyzeModuleDependencies(new ModuleAnalyzer(module));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<ArtifactSpec> getDependencies() {
        return this.dependencies;
    }

    @Override
    public ArtifactSpec findWildFlySwarmBootstrapJar() {
        return findArtifact(FractionDescriptor.THORNTAIL_GROUP_ID, WILDFLY_SWARM_BOOTSTRAP_ARTIFACT_ID, null, JAR, null, false);
    }

    @Override
    public ArtifactSpec findJBossModulesJar() {
        return findArtifact(JBOSS_MODULES_GROUP_ID, JBOSS_MODULES_ARTIFACT_ID, null, JAR, null, false);
    }

    @Override
    public ArtifactSpec findArtifact(String groupId, String artifactId, String version, String packaging, String classifier) {
        return findArtifact(groupId, artifactId, version, packaging, classifier, true);
    }

    @Override
    public ArtifactSpec findArtifact(String groupId, String artifactId, String version, String packaging, String classifier, boolean includeTestScope) {
        for (ArtifactSpec each : this.dependencies) {
            if (groupId != null && !groupId.equals(each.groupId())) {
                continue;
            }

            if (artifactId != null && !artifactId.equals(each.artifactId())) {
                continue;
            }

            if (version != null && !version.equals(each.version())) {
                continue;
            }

            if (packaging != null && !packaging.equals(each.type())) {
                continue;
            }

            if (classifier != null && !classifier.equals(each.classifier())) {
                continue;
            }

            if (!includeTestScope && each.scope.equals("test")) {
                continue;
            }

            return each;
        }

        return null;
    }

    public ResolvedDependencies analyzeDependencies(boolean autodetect, DeclaredDependencies declaredDependencies) throws Exception {

        // resolve to local files
        resolveDependencies(declaredDependencies, autodetect);

        // sort out removals, modules, etc
        analyzeFractionManifests();
        analyzeRemovableDependencies(declaredDependencies);

        this.dependencies.stream()
                .filter(e -> !this.removableDependencies.contains(e))
                .forEach(e -> this.applicationManifest.addDependency(e.mavenGav()));

        analyzeModuleDependencies();

        return this;
    }

    /**
     * Resolve declared dependencies to local files, aka turning them into @{@link ResolvedDependencies)
     *
     * @param declaredDependencies
     * @throws Exception
     */
    private void resolveDependencies(DeclaredDependencies declaredDependencies, boolean autodetect) throws Exception {
        this.dependencies.clear();

        // remove thorntail-runner dependencies, if some of them are needed, they should be re-added later
        Collection<ArtifactSpec> explicitDependencies = new ArrayList<>(declaredDependencies.getDirectDependencies());

        declaredDependencies.runnerDependency().ifPresent(runner -> {
            filterOutRunnerDependencies(runner, explicitDependencies);
        });

        // resolve the explicit deps to local files
        // expand to transitive if these are not pre-solved
        boolean resolveExplicitsTransitively = !declaredDependencies.isPresolved() || autodetect;
        Map<Boolean, List<ArtifactSpec>> partitioned = explicitDependencies.stream()
                .collect(Collectors.partitioningBy(declaredDependencies::isComplete));
        List<ArtifactSpec> complete = partitioned.get(true);
        List<ArtifactSpec> incomplete = partitioned.get(false);

        Collection<ArtifactSpec> resolvedIncompleteExplicitDependencies = resolveExplicitsTransitively ?
                resolver.resolveAllArtifactsTransitively(incomplete, false) :
                resolver.resolveAllArtifactsNonTransitively(incomplete);
        Collection<ArtifactSpec> resolvedCompleteExplicitDependencies = resolver.resolveAllArtifactsNonTransitively(complete);

        Collection<ArtifactSpec> resolvedExplicitDependencies = new LinkedHashSet<>();
        resolvedExplicitDependencies.addAll(resolvedCompleteExplicitDependencies);
        resolvedExplicitDependencies.addAll(resolvedIncompleteExplicitDependencies);

        this.dependencies.addAll(resolvedExplicitDependencies);

        Collection<ArtifactSpec> inputSet;
        Collection<ArtifactSpec> resolvedTransientDependencies;
        // resolve transitives if not pre-computed (i.e. from maven/gradle plugin)
        if (declaredDependencies.getTransientDependencies().isEmpty()) {
            inputSet = explicitDependencies;
            Collection<ArtifactSpec> filtered = inputSet
                    .stream()
                    .filter(dep -> dep.type().equals(JAR)) // filter out composite types, like ear, war, etc
                    .collect(Collectors.toList());

            resolvedTransientDependencies = resolver.resolveAllArtifactsTransitively(
                    filtered, false
            );

            this.dependencies.addAll(resolvedTransientDependencies);
        } else {
            // if transitive deps are pre-computed, resolve them to local files if needed
            inputSet = declaredDependencies.getTransientDependencies();
            Collection<ArtifactSpec> filtered = inputSet
                    .stream()
                    .filter(dep -> dep.type().equals(JAR))
                    .collect(Collectors.toList());

            resolvedTransientDependencies = Collections.emptySet();
            if (filtered.size() > 0) {

                resolvedTransientDependencies = resolver.resolveAllArtifactsNonTransitively(filtered);
                this.dependencies.addAll(resolvedTransientDependencies);
            }
        }

        // add the remaining transitive ones that have not been filtered
        Collection<ArtifactSpec> remainder = new ArrayList<>(inputSet);
        remainder.removeAll(resolvedTransientDependencies);

        this.dependencies.addAll(
                resolver.resolveAllArtifactsNonTransitively(remainder)
        );

        // populate the dependency map for faster lookups
        this.dependencies.forEach(s -> dependencyMap.put(s.mavenGav(), s));
    }

    private void filterOutRunnerDependencies(ArtifactSpec runnerJar, Collection<ArtifactSpec> explicitDependencies) {
        removableDependencies.add(runnerJar);
        explicitDependencies.remove(runnerJar);

        mavenDependencies(runnerJar.file)
                .stream()
                .map(ArtifactSpec::fromMavenDependencyDescription)
                .forEach(removableDependencies::add);
    }

    private void analyzeModuleDependencies() {
        this.dependencies.stream()
                .filter(e -> e.type().equals(JAR))
                .map(e -> e.file)
                .flatMap(ResolvedDependencies::findModuleXmls)
                .forEach(this::analyzeModuleDependencies);

    }

    private void analyzeModuleDependencies(ModuleAnalyzer analyzer) {
        List<ArtifactSpec> thorntailDependencies = analyzer.getDependencies();

        // ModuleAnalyzer looks for dependencies in a predefined location and assumes that the folder conforms to the
        // Maven repository layout. If it doesn't find the dependency in that location, then the ModuleAnalyzer will
        // return an ArtifactSpec object that does not contain the file location. But when using the Gradle plugin, it
        // is highly possible that the dependency was already retrieved and stored in the Gradle dependency cache.

        this.moduleDependencies.addAll(
                thorntailDependencies.stream()
                        .map(s -> s.file != null ? s : dependencyMap.getOrDefault(s.mavenGav(), s))
                        .collect(Collectors.toList())
        );
    }

    private void analyzeFractionManifests() {
        this.dependencies.stream()
                .map(e -> fractionManifest(e.file))
                .filter(Objects::nonNull)
                .peek(this.fractionManifests::add)
                .forEach((manifest) -> {
                    String module = manifest.getModule();
                    if (module != null) {
                        this.applicationManifest.addBootstrapModule(module);
                    }
                });

        this.dependencies.stream()
                .filter(e -> isFractionJar(e.file) || isConfigApiModulesJar(e.file))
                .forEach((artifact) -> this.applicationManifest.addBootstrapArtifact(artifact.mavenGav()));
    }

    /**
     * Removable are basically all dependencies that are brought in by fractions.
     */
    private void analyzeRemovableDependencies(DeclaredDependencies declaredDependencies) throws Exception {

        Collection<ArtifactSpec> bootstrapDeps = this.dependencies.stream()
                .filter(e -> isFractionJar(e.file))
                .collect(Collectors.toSet());
        if (removeAllThorntailLibs) {
            String whitelistProperty = System.getProperty("thorntail.runner.user-dependencies");
            Set<String> whitelist = new HashSet<>();
            if (whitelistProperty != null) {
                whitelist.addAll(asList(whitelistProperty.split(",")));
            }
            Set<String> uniqueMavenDependencies = new HashSet<>();
            this.dependencies.stream()
                    .map(e -> e.file)
                    .flatMap(e -> mavenDependencies(e).stream())
                    .forEach(uniqueMavenDependencies::add);
            this.fractionManifests.stream()
                    .flatMap(manifest -> manifest.getMavenDependencies().stream())
                    .forEach(uniqueMavenDependencies::add);

            uniqueMavenDependencies
                    .stream()
                    .map(ArtifactSpec::fromMavenDependencyDescription)
                    .forEach(this.removableDependencies::add);

            removableDependencies.addAll(bootstrapDeps);

            removableDependencies.removeIf(dep -> whitelist.contains(dep.mscGav()));
        } else {
            List<ArtifactSpec> nonBootstrapDeps = new ArrayList<>();
            nonBootstrapDeps.addAll(declaredDependencies.getDirectDependencies());
            nonBootstrapDeps.removeAll(bootstrapDeps);
            Collection<ArtifactSpec> nonBootstrapTransitive;
            // re-resolve the application's dependencies minus any of our swarm dependencies
            if (declaredDependencies.isPresolved()) {
                // Add the dependencies without querying for anything else.
                nonBootstrapTransitive = new HashSet<>();
                nonBootstrapDeps.stream()
                        .filter(s -> !FractionDescriptor.THORNTAIL_GROUP_ID.equals(s.groupId()))
                        .forEach(s -> {
                            nonBootstrapTransitive.add(s);
                            nonBootstrapTransitive.addAll(declaredDependencies.getTransientDependencies(s));
                        });
            } else {
                nonBootstrapTransitive = resolver.resolveAllArtifactsTransitively(nonBootstrapDeps, true);
            }

            // do not remove .war or .rar or anything else weird-o like.
            Set<ArtifactSpec> justJars = this.dependencies
                    .stream()
                    .filter(e -> e.type().equals(JAR))
                    .collect(Collectors.toSet());

            this.removableDependencies.addAll(justJars);
            this.removableDependencies.removeAll(nonBootstrapTransitive);
        }
    }

    Set<ArtifactSpec> getRemovableDependencies() {
        return this.removableDependencies;
    }

    @Override
    public boolean isRemovable(Node node) {
        Asset asset = node.getAsset();
        if (asset == null) {
            return false;
        }

        if (removableCheckSums == null) {
            initCheckSums();
        }

        try (final InputStream inputStream = asset.openStream()) {
            String checksum = ChecksumUtil.calculateChecksum(inputStream);

            return this.removableCheckSums.contains(checksum);
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    private synchronized void initCheckSums() {
        if (removableCheckSums == null) {
            removableCheckSums = removableDependencies.stream()
                    .map(this::checksum)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }

    private String checksum(ArtifactSpec spec) {
        if (spec.sha1sum != null) {
            return spec.sha1sum;
        }

        try {
            try (FileInputStream stream = new FileInputStream(spec.file)) {
                return ChecksumUtil.calculateChecksum(stream);
            }
        } catch (Exception any) {
            any.printStackTrace();
            return null;
        }
    }

    protected boolean isConfigApiModulesJar(File file) {
        if (file == null) {
            return false;
        }

        try (JarFile jar = JarFiles.create(file)) {
            return jar.getEntry("wildfly-swarm-modules.conf") != null;
        } catch (IOException e) {
            // ignore
        }
        return false;

    }

    public static boolean isFractionJar(File file) {
        if (file == null) {
            return false;
        }

        try (JarFile jar = JarFiles.create(file)) {
            return jar.getEntry(FractionManifest.CLASSPATH_LOCATION) != null;
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    protected List<String> mavenDependencies(File file) {
        if (file == null) {
            return Collections.emptyList();
        }

        List<String> resultList = new ArrayList<>();

        try (JarFile jar = JarFiles.create(file)) {
            ZipEntry entry = jar.getEntry("META-INF/maven-dependencies.txt");
            if (entry != null) {
                InputStream inputStream = jar.getInputStream(entry);
                try (InputStreamReader streamReader = new InputStreamReader(inputStream);
                     BufferedReader reader = new BufferedReader(streamReader)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        resultList.add(line);
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return resultList;
    }

    protected FractionManifest fractionManifest(File file) {
        try (JarFile jar = JarFiles.create(file)) {
            ZipEntry entry = jar.getEntry(FractionManifest.CLASSPATH_LOCATION);
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return new FractionManifest(in);
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    void setProjectAsset(ProjectAsset projectAsset) {
        if (!this.applicationManifest.isHollow()) {
            this.projectAsset = projectAsset;
            this.applicationManifest.setAsset(this.projectAsset.getName());
        }
    }

    protected WildFlySwarmManifest getWildFlySwarmManifest() {
        return this.applicationManifest;
    }

    @Override
    public Set<ArtifactSpec> getModuleDependencies() {
        return moduleDependencies;
    }

    private static final String JAR = "jar";

    private final WildFlySwarmManifest applicationManifest = new WildFlySwarmManifest();

    private final List<FractionManifest> fractionManifests = new ArrayList<>();

    private final Set<ArtifactSpec> dependencies = new HashSet<>();

    private final Map<String, ArtifactSpec> dependencyMap = new HashMap<>();

    private final Set<ArtifactSpec> removableDependencies = new HashSet<>();

    private volatile Set<String> removableCheckSums;

    private final Set<ArtifactSpec> moduleDependencies = new HashSet<>();

    private ProjectAsset projectAsset;

    private ArtifactResolver resolver;

    private final boolean removeAllThorntailLibs;
}
