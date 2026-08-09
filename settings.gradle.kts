pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ZhiBan"
include(":app")
include(":agent:contracts")
include(":agent:provider")
include(":agent:context")
include(":agent:tools")
include(":agent:governance")
include(":agent:skills")
include(":agent:mcp")
include(":agent:memory")
include(":agent:runtime")
include(":agent:feature-ask")
