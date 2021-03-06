// tag::artifact-only-dependency-declaration[]
repositories {
    ivy {
        url = uri("https://ajax.googleapis.com/ajax/libs")
        layout("pattern", Action<IvyPatternRepositoryLayout> {
            artifact("[organization]/[revision]/[module].[ext]")
        })
    }
}

configurations {
    create("js")
}

dependencies {
    "js"("jquery:jquery:3.2.1@js")
}
// end::artifact-only-dependency-declaration[]

task<Copy>("copyLibs") {
    from(configurations["js"])
    into("$buildDir/libs")
}
