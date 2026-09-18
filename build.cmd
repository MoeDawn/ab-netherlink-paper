@echo off
set "JAVA_HOME=C:\jdk25\jdk-25.0.4.1+1"
set "Path=%JAVA_HOME%\bin;%Path%"
call C:\gradle\gradle-9.1.0\bin\gradle.bat build --no-daemon %*
