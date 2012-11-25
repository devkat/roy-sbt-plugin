sbtPlugin := true

name := "roy-sbt-plugin"

organization := "net.devkat"

version := "0.1"

scalaVersion := "2.9.2"

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/repo/"

libraryDependencies ++= Seq(
  "org.mozilla" % "rhino" % "1.7R4",
  "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.1-seq"
)

