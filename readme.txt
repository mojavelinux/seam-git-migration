This script will checkout all the Seam 3 source code as local git repositories
in import-phase1, then clone them into git repositories in import-phase2 that
have a remote that points to github.

To run the script, you'll need groovy on your path and the following Ivy xml
stanza in ~/.groovy/grapeConfig.xml:

<ivysettings>
 <property name="ivy.checksums" value=""/>
 <settings defaultResolver="downloadGrapes"/>
 <resolvers>
   <chain name="downloadGrapes">
     <filesystem name="cachedGrapes">
       <ivy pattern="${user.home}/.groovy/grapes/[organisation]/[module]/ivy-[revision].xml"/>
       <artifact pattern="${user.home}/.groovy/grapes/[organisation]/[module]/[type]s/[artifact]-[revision].[ext]"/>
     </filesystem>
     <ibiblio name="codehaus" root="http://repository.codehaus.org/" m2compatible="true"/>
     <ibiblio name="ibiblio" m2compatible="true"/>
     <ibiblio name="java.net2" root="http://download.java.net/maven/2/" m2compatible="true"/>
   </chain>
 </resolvers>
</ivysettings>
