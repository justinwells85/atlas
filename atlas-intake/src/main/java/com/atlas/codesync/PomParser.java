package com.atlas.codesync;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Parses a pom.xml string into a {@link PomFacts} record (M4). Uses
 * Maven's MavenXpp3Reader for the XML — the same reader Maven itself
 * uses, so it tolerates the various spacing / namespacing / comment
 * patterns real poms carry. No property resolution and no parent
 * inheritance traversal: the literal declarations are surfaced as-is.
 */
@Component
public class PomParser {

    private static final String SPRING_BOOT_PARENT_GROUP = "org.springframework.boot";
    private static final String SPRING_BOOT_PARENT_ARTIFACT = "spring-boot-starter-parent";

    public PomFacts parse(String pomXml) {
        Model model;
        try {
            model = new MavenXpp3Reader().read(new StringReader(pomXml));
        } catch (IOException | XmlPullParserException e) {
            throw new IllegalArgumentException("pom.xml could not be parsed: " + e.getMessage(), e);
        }
        Parent parent = model.getParent();
        Properties props = model.getProperties() != null ? model.getProperties() : new Properties();

        String parentGroup = parent != null ? parent.getGroupId() : null;
        String parentArtifact = parent != null ? parent.getArtifactId() : null;
        String parentVersion = parent != null ? parent.getVersion() : null;

        String framework = null;
        String frameworkVersion = null;
        if (SPRING_BOOT_PARENT_GROUP.equals(parentGroup)
                && SPRING_BOOT_PARENT_ARTIFACT.equals(parentArtifact)) {
            framework = "Spring Boot";
            frameworkVersion = parentVersion;
        }

        return new PomFacts(
                model.getGroupId(),
                model.getArtifactId(),
                model.getVersion(),
                parentGroup,
                parentArtifact,
                parentVersion,
                model.getPackaging() != null ? model.getPackaging() : "jar",
                "Java",
                props.getProperty("java.version"),
                framework,
                frameworkVersion,
                "Maven",
                model.getModules() != null ? List.copyOf(model.getModules()) : List.of(),
                toCoords(model.getDependencies()));
    }

    private static List<DependencyCoords> toCoords(List<Dependency> deps) {
        if (deps == null) return List.of();
        List<DependencyCoords> out = new ArrayList<>(deps.size());
        for (Dependency d : deps) {
            out.add(new DependencyCoords(d.getGroupId(), d.getArtifactId(), d.getVersion()));
        }
        return out;
    }
}
