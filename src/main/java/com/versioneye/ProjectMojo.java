package com.versioneye;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.jackson.map.ObjectMapper;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.versioneye.dto.ProjectDependency;
import com.versioneye.dto.ProjectJsonResponse;
import com.versioneye.utils.CollectionUtils;
import com.versioneye.utils.DependencyUtils;
import com.versioneye.utils.HttpUtils;
import com.versioneye.utils.JsonUtils;
import com.versioneye.utils.PropertiesUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

/**
 * Methods required to deal with projects resource
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class ProjectMojo extends SuperMojo {

    protected ByteArrayOutputStream getTransitiveDependenciesJsonStream(String nameStrategy) throws Exception
    {
        PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
        DependencyNode root = getDependencyNode(nlg);
        List<Artifact> transDependencies = DependencyUtils.collectAllDependencies(nlg.getDependencies(true));
        List<Artifact> directDependencies = DependencyUtils.collectDirectDependencies(root.getChildren());
        List<Dependency> dependencies = new ArrayList<>();
        for (org.eclipse.aether.artifact.Artifact artifact : transDependencies) {
            Dependency dep = new Dependency();
            dep.setGroupId(artifact.getGroupId());
            dep.setArtifactId(artifact.getArtifactId());
            dep.setVersion(artifact.getVersion());
            if (directDependencies.contains(artifact)) {
                dep.setScope("direct");
            } else {
                dep.setScope("transitive");
            }
            dependencies.add(dep);
        }
        JsonUtils jsonUtils = new JsonUtils();
        return jsonUtils.dependenciesToJson(project, dependencies, null, nameStrategy);
    }

    protected ByteArrayOutputStream getDirectDependenciesJsonStream(String nameStrategy) throws IOException
    {
        List<Plugin> plugins = new ArrayList<>();
        if (trackPlugins){
            plugins = getPluginsFromXml();
        }

        List<Dependency> dependencies = project.getDependencies();

        if (!ignoreDependencyManagement && project.getDependencyManagement() != null &&
          CollectionUtils.collectionNotEmpty(project.getDependencyManagement().getDependencies())) {
              dependencies.addAll(project.getDependencyManagement().getDependencies());
        }

        List<Dependency> filteredDependencies = filterForScopes(dependencies);

        JsonUtils jsonUtils = new JsonUtils();
        return jsonUtils.dependenciesToJson(project, filteredDependencies, plugins, nameStrategy);
    }

    protected Map<String, Object> getDirectDependenciesJsonMap(String nameStrategy) {
        List<Dependency> dependencies = project.getDependencies();
        if (dependencies == null || dependencies.isEmpty()){
            return null;
        } else {
            iterateThrough(dependencies);
        }
        JsonUtils jsonUtils = new JsonUtils();
        List<Map<String, Object>> dependencyHashes = jsonUtils.getDependencyHashes(dependencies, project.getPluginManagement().getPlugins());
        return jsonUtils.getJsonPom(project, dependencyHashes, nameStrategy);
    }

    protected ByteArrayOutputStream getDirectArtifactsJsonStream() throws Exception
    {
        DependencyNode root = getDependencyNode(new PreorderNodeListGenerator());
        List<Artifact> directDependencies = DependencyUtils.collectDirectDependencies(root.getChildren());
        JsonUtils jsonUtils = new JsonUtils();
        return jsonUtils.artifactsToJson(directDependencies);
    }

    protected DependencyNode getDependencyNode(PreorderNodeListGenerator nlg) throws Exception {
        CollectRequest collectRequest = DependencyUtils.getCollectRequest(project, repos);
        DependencyNode root = system.collectDependencies(session, collectRequest).getRoot();
        DependencyRequest dependencyRequest = new DependencyRequest(root, null);
        system.resolveDependencies(session, dependencyRequest);
        root.accept(nlg);
        return root;
    }

    protected void prettyPrint0End() {
        getLog().info(".");
        getLog().info("There are no dependencies in this project! - " + project.getId() );
        getLog().info(".");
    }

    protected void prettyPrint(ProjectJsonResponse response) throws IOException {
        getLog().info(".");
        getLog().info("Project name: " + response.getName());
        getLog().info("Project id: "   + response.getId());
        getLog().info("Dependencies: " + response.getDep_number());
        getLog().info("Outdated: "     + response.getOut_number());
        for (ProjectDependency dependency : response.getDependencies() ){
            if (!dependency.getOutdated()){
                continue;
            }

            getLog().info(" - " + dependency.getProd_key() + ":" + dependency.getVersion_requested() + " -> " + dependency.getVersion_current());
        }
        getLog().info("");
        String projectID = (String) mavenSession.getTopLevelProject().getContextValue("veye_project_id");
        getLog().info("You can find your updated project here: " + fetchBaseUrl() + "/user/projects/" + projectID);
        getLog().info("");
    }

    protected ProjectJsonResponse updateExistingProject(String resource, String projectId, ByteArrayOutputStream outStream) throws Exception {
        String apiKey = fetchApiKey();
        String url = fetchBaseUrl() + apiPath + resource + "/" + projectId + "?api_key=" + apiKey;
        Reader reader = HttpUtils.post(url, outStream.toByteArray(), "project_file", null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(reader, ProjectJsonResponse.class );
    }


    protected ProjectJsonResponse createNewProject(String resource, ByteArrayOutputStream outStream) throws Exception {
        String apiKey = fetchApiKey();
        String url = fetchBaseUrl() + apiPath + resource + apiKey;
        Reader reader = HttpUtils.post(url, outStream.toByteArray(), "upload", visibility, name, organisation, team);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(reader, ProjectJsonResponse.class );
    }

    protected void merge(String childId) {
        if (!mergeAfterCreate) {
            return ;
        }
        try {
            if (mavenSession.getTopLevelProject().getId().equals(mavenSession.getCurrentProject().getId())){
                return ;
            }

            String parentProjectId = (String) mavenSession.getTopLevelProject().getContextValue("veye_project_id");
            getLog().debug("parentProjectId: " + parentProjectId);
            String url = fetchBaseUrl() + apiPath + "/projects/" + parentProjectId + "/merge/" + childId + "?api_key=" + fetchApiKey();

            String response = HttpUtils.get(url);
            getLog().debug("merge response: " + response);
        } catch (Exception ex) {
            getLog().error(ex);
        }
    }

    protected void writeProperties(ProjectJsonResponse response) throws IOException {
        Properties properties = fetchProjectProperties();
        if (response.getId() != null) {
            properties.setProperty("project_id", response.getId());
        }
        PropertiesUtils utils = new PropertiesUtils();
        utils.writeProperties(properties, getPropertiesPath());
    }

    private void iterateThrough(List<Dependency> dependencies) {
        for(Dependency dep: dependencies){
            getLog().info(" - dependency: " + dep.getGroupId() + "/" + dep.getArtifactId() + " " + dep.getVersion());
        }
    }

    private List<Plugin> getPluginsFromXml() {
        List<Plugin> plugins = new ArrayList<>();
        try {
            File pom = project.getModel().getPomFile();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pom);
            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            XPathExpression expr = xpath.compile("//plugins/plugin");

            NodeList nl = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
            for (int i = 0 ; i < nl.getLength() ; i++){
                Node node = nl.item(i);
                Plugin plugin = new Plugin();
                fillPlugin(node, plugin);
                if (plugin.getGroupId() != null && plugin.getArtifactId() != null){
                    plugins.add(plugin);
                }
            }
        } catch (Exception exc){
            getLog().error(exc);
        }
        return plugins;
    }

    private void fillPlugin(Node node, Plugin plugin){
        for (int xi = 0 ; xi < node.getChildNodes().getLength() ; xi++ ){
            Node child = node.getChildNodes().item(xi);
            if (child == null){
                return ;
            }

            if ("groupId".equals(child.getNodeName())){
                plugin.setGroupId(child.getTextContent().trim());
            }
            if ("artifactId".equals(child.getNodeName())){
                plugin.setArtifactId(child.getTextContent().trim());
            }
            if ("version".equals(child.getNodeName())){
                String version = parseVersionString( child.getTextContent().trim() );
                plugin.setVersion(version);
            }
        }
    }

    private String parseVersionString(String version) {

        String localVersion = version;

        if (localVersion.startsWith("${")){
            String verValue = localVersion.replaceAll("\\$\\{", "").replaceAll("\\}", "");
          localVersion = (String) project.getProperties().get(verValue);
        }
        return localVersion;
    }

    private List<Dependency> filterForScopes(List<Dependency> dependencies) {
        if (StringUtils.isBlank(skipScopes) || CollectionUtils.collectionIsEmpty(dependencies))
            return dependencies;

        String[] scopes = skipScopes.split(",");
        List<Dependency> filtered = new ArrayList<>();
        for (Dependency dependency : dependencies){
            boolean ignoreScope = false;
            for ( String scope : scopes ) {
                if (scope != null
                  && dependency != null
                  && dependency.getScope() != null
                  && dependency.getScope().equalsIgnoreCase(scope))
                {
                    ignoreScope = true;
                }
            }
            if (!ignoreScope){
                filtered.add(dependency);
            }
        }
        return filtered;
    }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

  }
}
