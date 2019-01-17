// Copyright 2018,2019 Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.json.mojo;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class TestMain implements Main {
  private URL[] classpath;
  private URL classpathResource;
  private String className;
  private File schemaFile;
  private String resourceName;
  private boolean includeDeprecated;
  private Map<URL, URL> schemas = new HashMap<>();
  private String kubernetesVersion;
  private boolean includeAdditionalProperties;
  private boolean supportObjectReferences;
  private File markdownFile;

  TestMain() throws MalformedURLException {
    classpathResource = new URL("file:abc");
  }

  boolean isIncludeDeprecated() {
    return includeDeprecated;
  }

  URL[] getClasspath() {
    return classpath;
  }

  String getResourceName() {
    return resourceName;
  }

  void setClasspathResource(URL classpathResource) {
    this.classpathResource = classpathResource;
  }

  String getClassName() {
    return className;
  }

  File getSchemaFile() {
    return schemaFile;
  }

  File getMarkdownFile() {
    return markdownFile;
  }

  URL getCacheFor(URL schemaUrl) {
    return schemas.get(schemaUrl);
  }

  String getKubernetesVersion() {
    return kubernetesVersion;
  }

  boolean isIncludeAdditionalProperties() {
    return includeAdditionalProperties;
  }

  boolean isSupportObjectReferences() {
    return supportObjectReferences;
  }

  @Override
  public void setSupportObjectReferences(boolean supportObjectReferences) {
    this.supportObjectReferences = supportObjectReferences;
  }

  @Override
  public void setKubernetesVersion(String kubernetesVersion) {
    this.kubernetesVersion = kubernetesVersion;
  }

  @Override
  public void defineSchemaUrlAndContents(URL schemaURL, URL cacheUrl) {
    schemas.put(schemaURL, cacheUrl);
  }

  @Override
  public void setIncludeDeprecated(boolean includeDeprecated) {
    this.includeDeprecated = includeDeprecated;
  }

  @Override
  public void setIncludeAdditionalProperties(boolean includeAdditionalProperties) {
    this.includeAdditionalProperties = includeAdditionalProperties;
  }

  @Override
  public void defineClasspath(URL... classpath) {
    this.classpath = classpath;
  }

  @Override
  public URL getResource(String name) {
    resourceName = name;
    return classpathResource;
  }

  @Override
  public void generateSchema(String className, File outputFile) {
    this.className = className;
    this.schemaFile = outputFile;
  }

  @Override
  public void generateMarkdown(File markdownFile) {
    this.markdownFile = markdownFile;
  }
}
