package org.primeframework.gradle.plugins

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.tasks.TaskDependency

/**
 * The release-git plugin
 *
 * @author James Humphrey
 */
class ReleasePlugin implements Plugin<Project> {

  def void apply(Project project) {

    // configuration for this plugin
    project.extensions.add("releasePlugin", new ReleasePluginConfiguration())
    project.extensions.getByType(ReleasePluginConfiguration.class).inversoftIvyGitRepo = "$project.gradle.gradleUserHomeDir/inversoft-ivy-git-repo"

    // create a digest configuration.  This is where the sha1 and md5 artifacts go
    project.configurations.add("digest") { configuration ->
      configuration.visible = false
    }

    File releaseGitDir = new File(project.releasePlugin.inversoftIvyGitRepo)
    String inversoftGitRepo = "git@git.inversoft.com:repositories/ivy.git"

    // do some preparation when the task graph is ready
    project.gradle.taskGraph.whenReady { taskGraph ->
      if (taskGraph.hasTask(":release")) {

        // set the project status to release
        project.status = "release"

        def privateOrPublic = project.releasePlugin.publicRepo ? "public" : "private"
        println "Releasing to the $privateOrPublic Inversoft Apache Ivy repository"

        project.repositories {
          ivy {
            name "releaseRepository"
            ivyPattern "file:///$project.releasePlugin.inversoftIvyGitRepo/repository/$privateOrPublic/[organisation]/[module]/[revision]/ivy.xml"
            artifactPattern "file:///$project.releasePlugin.inversoftIvyGitRepo/repository/$privateOrPublic/[organisation]/[module]/[revision]/[type]s/[artifact]-[revision].[ext]"
          }
        }

        // set the upload archives repository
        project.uploadArchives {
          repositories {
            try {
              add project.repositories.releaseRepository
            } catch (Exception e) {
              throw new GradleException("""You must first define a repository with the name 'releaseRepository'.
\nEx:
    repositories {
      ivy {
        name "releaseRepository"
        ivyPattern "file://repos/[organisation]/[module]/[revision]/ivy.xml"
        artifactPattern "file:///repo/[organisation]/[module]/[revision]/[type]s/[artifact]-[revision].[ext]"
      }
    }

""")
            }
          }
        }

        // set the upload sources directory
        project.uploadSources {
          repositories {
            add project.repositories.releaseRepository
          }
        }

        project.uploadDigest {
          repositories {
            add project.repositories.releaseRepository
          }
        }
      }
    }

    /**
     * Makes some preparations prior to performing the release:
     *  1.  release directory isn't dirty (no changes)
     *  2.  Everything that has been committed has been pushed
     *  3.  everything in the release directory has been pulled down from the git remote
     *  4.  that, in fact, you are executing this from a git project
     *  5.  that a tag for the version you are releasing hasn't yet been pushed
     *  6.  The project version and it's dependency versions don't depend on any integration
     * builds (versions with SNAPSHOT in it)
     *
     */
    project.task("a-prepareRelease", dependsOn: "clean") << {

      // Check if this is a working copy
      def f = new File(".git")
      if (!f.exists()) {
        throw new GradleException("You can only run a release from a Git repository.")
      }

      // Do a pull
      println "Updating working copy"
      def proc = "git pull".execute()
      proc.waitForOrKill(20000)
      if (proc.exitValue() != 0) {
        throw new GradleException("Unable to pull from remote Git repository. Git output is:\n\n$proc.text")
      }

      // See if the working copy is ahead
      String status = "git status -sb".execute().text.trim()
      if (status.toLowerCase().contains("ahead")) {
        throw new GradleException("Your git working copy appears to have local changes that haven't been pushed.")
      }

      // Check for local modifications
      status = "git status --porcelain".execute().text.trim()
      if (!status.isEmpty() && !project.releasePlugin.releaseDirty) {
        throw new GradleException("Cannot release from a dirty directory. Git status output is:\n\n" + status)
      }

      // check tag is available
      isTagAvailable(project.version)

      // check integration versions
      checkIntegrationVersions(project, "compile", "runtime")
    }

    /**
     * Tags this git repository with the version
     */
    project.task("zz-tag") << {
      if (!project.releasePlugin.testRelease) {
        println "Creating tag $project.version"
        def proc = ["git", "tag", "-a", project.version, "-m", "Tagging $project.version"].execute()
        proc.waitFor()
        proc = "git push --tags".execute()
        proc.waitFor()
      }
    }

    /**
     * The pre-publish step clones or pulls the release repository
     */
    project.task("c-prePublish", dependsOn: "sourceJar") << {

      if (!releaseGitDir.exists()) {
        println "Cloning $inversoftGitRepo for the first time.  This could take a while..."
        def proc = "git clone $inversoftGitRepo $project.releasePlugin.inversoftIvyGitRepo".execute()
        proc.waitFor()
      } else {
        println "Pulling $inversoftGitRepo to synchronize local to remote..."
        def proc = "git pull".execute(null, releaseGitDir);
        proc.waitForOrKill(20000)
        if (proc.exitValue() != 0) {
          throw new GradleException("Unable to pull from remote Git repository. Git output is:\n\n$proc.text")
        }
      }

      if (project.releasePlugin.addChecksums) {
        addChecksums(project)
      }
    }

    /**
     * Performs the git commands to add, commit, and push the new artifacts to the release repository
     */
    project.task("z-publish") << {
      if (!project.releasePlugin.testRelease) {
        println "Publishing artifacts to remote repository..."
        def addProc = "git add .".execute(null, releaseGitDir)
        addProc.waitFor()
        def commitProc = ["git", "commit", "-a", "-m", "Publishing $project.name $project.version"].execute(null, releaseGitDir)
        commitProc.waitForOrKill(20000)
        if (commitProc.exitValue() != 0) {
          throw new GradleException("Unable to commit to remote git repository. Git output is:\n\n$proc.text")
        }
        def pushProc = "git push origin master".execute(null, releaseGitDir)
        pushProc.waitForOrKill(20000)
        if (pushProc.exitValue() != 0) {
          throw new GradleException("Unable to push to remote Git repository. Git output is:\n\n$proc.text")
        }
      }
    }

    /**
     * Performs upload steps for archive, source, and digest artifacts
     */
    project.task("upload", dependsOn: ["uploadArchives", "uploadSources", "uploadDigest"]) {
      // stub.  defers to depends on set
    }

    /**
     * Main entry point to the release plugin.
     *
     * gradle dependsOn is ordered alphabetically so alpha letters have been
     * prepended to the front of the task names to force gradle ordering
     */
    project.task("release", dependsOn: ["a-prepareRelease", "build", "c-prePublish", "upload", "z-publish", "zz-tag"]) << {
      // stub. defers to depends on set
    }
  }

  /**
   * Helper method to
   *
   * @param project the project
   * @param configurations the configurations
   */
  void checkIntegrationVersions(Project project, String... configurations) {

    if (project.version.contains("SNAPSHOT")) {
      throw new GradleException("""Invalid version [$project.version].  You cannot release a version that contains
the integration designator 'SNAPSHOT'""")
    }

    configurations.each { configuration ->
      project.configurations.getByName(configuration).dependencies.each { dep ->
        if (dep.group != null && dep.version.toLowerCase().contains("SNAPSHOT")) {
          throw new GradleException("Invalid integration version for release:  [$dep.group:$dep.name:$dep.version]")
        }
      }
    }
  }

  /**
   * Helper method to check git tag availability
   *
   * @param tag the tag
   */
  public void isTagAvailable(tag) {
    def proc = "git fetch -t".execute()
    proc.waitForOrKill(20000)
    if (proc.exitValue() != 0) {
      throw new GradleException("Unable to fetch tgsfrom remote Git repository. Git output is:\n\n$proc.text")
    }

    String versionTag = "git tag -l $tag".execute().text
    if (versionTag != null && versionTag.length() > 0) {
      throw new GradleException("Version $tag already exists")
    }
  }

  /**
   * Adds a md5 and sha1 checksum for each configuration defined in the release config
   *
   * @param project the Project object
   */
  void addChecksums(Project project) {

    // now iterate through each 'configuration' configured in the release config
    def checksumConfigurations = ["archives", "sources"]
    checksumConfigurations.each { configuration ->
      List<CheckSum> checksums = []

      project.configurations.getByName(configuration).artifacts.each { artifact ->
        project.ant.checksum(file: artifact.file, algorithm: "sha1", todir: "$project.buildDir/digest")
        project.ant.checksum(file: artifact.file, algorithm: "md5", todir: "$project.buildDir/digest")
        checksums.add(new CheckSum(artifact.name, "${artifact.extension}.sha1", artifact.type, new File("$project.buildDir/digest/${artifact.file.name}.sha1")))
        checksums.add(new CheckSum(artifact.name, "${artifact.extension}.md5", artifact.type, new File("$project.buildDir/digest/${artifact.file.name}.md5")))
      }

      checksums.each { checksum ->
        project.artifacts.add("digest", checksum)
      }
    }
  }

  /**
   * Models a checksum
   */
  class CheckSum implements PublishArtifact {

    String name;
    String extension;
    String type;
    File file;

    CheckSum(String name, String extension, String type, File file) {
      this.name = name
      this.extension = extension
      this.type = type
      this.file = file
    }

    @Override
    String getName() {
      return name
    }

    @Override
    String getExtension() {
      return extension
    }

    @Override
    String getType() {
      return type
    }

    @Override
    String getClassifier() {
      return null
    }

    @Override
    File getFile() {
      return file
    }

    @Override
    Date getDate() {
      return new Date()
    }

    @Override
    TaskDependency getBuildDependencies() {
      return null
    }

    @Override
    public String toString() {
      return "CheckSum{" +
        "name='" + name + '\'' +
        ", extension='" + extension + '\'' +
        ", type='" + type + '\'' +
        ", file=" + file +
        '}';
    }
  }

  /**
   * Configuration bean for the release plugin.
   *
   * If you had additional configurations that you're uploading,
   * you can add to this list in your project as follows:
   *
   * project.releasePlugin.checksumConfiguration.add "zips"
   */
  class ReleasePluginConfiguration {

    /**
     * Directory on disk where you want to store the inversoft apachy ivy git repository
     *
     * This is set when the plugin first gets applied in your project
     */
    def inversoftIvyGitRepo

    /**
     * Set to false if this you're releasing to the private inversoft repository
     */
    boolean publicRepo = true

    /**
     * Set to true if you want to release from a dirty directory
     */
    boolean releaseDirty = false

    /**
     * If true, the release plugin will not perform the publish and tag steps
     */
    boolean testRelease = false

    /**
     * adds the checksum files to the digest configuration if true.  defaults to true
     */
    boolean addChecksums = true
  }
}