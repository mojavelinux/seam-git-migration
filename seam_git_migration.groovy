#!/usr/bin/env groovy

// Go grab Groosh so we can do standard shell commands (http://groovy.codehaus.org/Groosh)
@Grapes([
    @Grab(group='org.codehaus.groovy.modules', module='groosh', version='0.3.6'),
    @GrabConfig(systemClassLoader=true)
])

import groosh.Groosh
Groosh.withGroosh(this)

// Groosh sends output to an output stream, but XmlSlurper needs an input stream, so using Piped streams and another thread to grab all the modules
def _in = new PipedInputStream()
def out = new PipedOutputStream(_in)

// git repositories seeded from svn
def phase1_dir = new File('import-phase1')
// fresh git clones of migrated svn repositories
def phase2_dir = new File('import-phase2')

// perhaps commandline arguments?
def phase1 = true
def phase2 = true

Thread.start {
  svn('list', '--xml', 'http://anonsvn.jboss.org/repos/seam/modules/').toStream(out)
}

def modules = new XmlSlurper().parse(_in).list.entry.name.collect { it.text() }
def sandbox = ['scheduling']
//def others = ['parent', 'dist', 'examples']
def others = ['build', 'dist', 'examples']

// testing overrides
//modules = []
//sandbox = []
//others = ['build']

def allsvn = modules + sandbox + others
def existing = ['mail': 'git://github.com/codylerum/seam-mail.git', 'catch': 'git://github.com/LightGuard/seam-exception-handling.git']

if (phase1) {

   phase1_dir.mkdir()
   cd(phase1_dir)
   
   modules.each { m ->
      clone_svn_repo(m, '/modules', m == 'wicket' ? false : true)
   }

   sandbox.each { s ->
      clone_svn_repo(s, '/sandbox/modules', false)
   }
   
   others.each { o ->
      clone_svn_repo(o, '/', o == 'parent' ? true : false)
   }
   
   cd('..')
}

if (phase2) {

   phase2_dir.mkdir()
   cd(phase2_dir)
   
   allsvn.each { r ->
      clone_git_repo("../$phase1_dir", r)
   }
   
   existing.each { n, r ->
      clone_existing_git_repo(n, r)
   }

   update_scm_urls(phase2_dir)
   
   // may be set from phase1
   clear_git_committer()

   commit_and_push(phase2_dir)
}

def clone_svn_repo(name, context, pull_tags) {
   def svn_uri = "http://anonsvn.jboss.org/repos/seam$context/$name"
   def trunk = 'trunk'
   def authorsFile = '../svn.authors'
   if (name == 'parent') {
      trunk += '/parent'
   }
   if (pull_tags) {
      git('svn', 'clone', svn_uri, '--no-metadata', '--no-minimize-url', "--trunk=$trunk", '--tags=tags', "--authors-file=$authorsFile") >> stdout
   }
   else {
      git('svn', 'clone', svn_uri, '--no-metadata', '--no-minimize-url', "--trunk=$trunk", "--authors-file=$authorsFile") >> stdout
   }
   if (pull_tags) {
      fix_tags(name)
   }
   cd(name)
   git('gc') >> stdout
   //git('gc', '--aggresive') >> stdout // this will take more time but will give us better results before we push
   cd('..')
}

def clone_git_repo(root, repo) {
   git('clone', "$root/$repo").waitForExit()
   cd(repo)
   def github_module = repo
   if (repo == 'remoting') {
      github_module = 'js-remoting'
   }
   else if (repo == 'xml') {
      github_module = 'xml-config'
   }
   else if (repo == 'build') {
      def build_tags = [
         [tag: 'seam-parent-1', commit: '9357363e30e78bd72ac18919373f109ca5090746', date: '2010-06-02 13:01:39 +0000'],
         [tag: 'seam-parent-2', commit: 'e37bf2b1180d2f93b500abc3c6b9b04f9b126831', date: '2010-07-13 17:04:27 +0000'],
         [tag: 'seam-parent-3', commit: '61ac7e5da2db99bc8f756aaf528703470e336574', date: '2010-08-06 16:22:11 +0000']
      ]
      build_tags.each { t ->
         set_git_committer('Pete Muir', 'pete.muir@jboss.org', t.date)
         git('tag', '-m', "[maven-scm] copy for tag ${t.tag}", '-a', t.tag, t.commit).waitForExit()
      }
   }
   else if (repo == 'dist') {
      set_git_committer('Pete Muir', 'pete.muir@jboss.org', '2010-06-02 13:27:17 +0000')
      git('tag', '-m', '[maven-scm] copy for tag b01', '-a', 'seam-bom-b01', '9a03ff3fbddad2d9ced3f89150872352e6457798').waitForExit()
   }
   git('remote', 'add', 'github', "git@github.com:seam/${github_module}.git").waitForExit()
   git('remote', 'rm', 'origin').waitForExit()
   git('remote', '-v') >> stdout
   cd('..')
}

def clone_existing_git_repo(name, repo) {
   git('clone', repo, name).waitForExit()
   cd(name)
   git('remote', 'add', 'github', "git@github.com:seam/${name}.git").waitForExit()
   git('remote', 'rm', 'origin').waitForExit()
   git('remote', '-v') >> stdout
   cd('..')
}

def fix_tags(repo) {
   cd(repo)
   git('for-each-ref', '--format=%(refname)', 'refs/remotes/tags/*').eachLine { tag_ref ->
      def tag = tag_ref.minus('refs/remotes/tags/')
      def tree = parse_rev("$tag_ref:", false)
      def parent_ref = tag_ref
      while (parse_rev("$parent_ref^:", true) == tree) {
         parent_ref = "$parent_ref^"
      }
      def parent = parse_rev(parent_ref, false)
      def merge = git('merge-base', 'refs/remotes/trunk', parent).text.trim()
      def target_ref
      if (merge == parent) {
         target_ref = parent
         println "$tag references master branch"
      }
      else {
         target_ref = tag_ref
         println "$tag has diverged from the master branch"
      }
      
      println "$tag revision = $target_ref"
      set_git_committer(log_meta(tag_ref, '%an'), log_meta(tag_ref, '%ae'), log_meta(tag_ref, '%ai'))
      pipe_meta(tag_ref, '%s') | git('tag', '-a', '-F', '-', tag, target_ref)
      git('update-ref', '-d', tag_ref)
   }
   cd('..')
}

def parse_rev(ref, verify) {
   if (verify) {
      return git('rev-parse', '--quiet', '--verify', ref).text.trim()
   }
   return git('rev-parse', ref).text.trim()
}

def log_meta(tag_ref, symbol) {
   return pipe_meta(tag_ref, symbol).text.trim()
}

def pipe_meta(tag_ref, symbol) {
   return git('log', '-1', "--pretty=\"format:$symbol\"", tag_ref)
}

def set_git_committer(name, email, date) {
   def env = groosh.getCurrentEnvironment()
   env.put('GIT_COMMITTER_NAME', name)
   env.put('GIT_COMMITTER_EMAIL', email)
   env.put('GIT_COMMITTER_DATE', date)
}

def clear_git_committer() {
   def env = groosh.getCurrentEnvironment()
   env.remove('GIT_COMMITTER_NAME')
   env.remove('GIT_COMMITTER_EMAIL')
   env.remove('GIT_COMMITTER_DATE')
}

def modifyFile(file, Closure processText) {
    def text = file.text
    file.write(processText(text))
}

def update_scm_urls(rootDir) {
   rootDir.eachFileRecurse({ f ->
      if (f.file && f.name ==~ /^(readme.txt|pom\.xml$)/) {
         modifyFile(f, { text ->
            text = (text =~ /\/persistence\/trunk\/tests-(weld-se|jboss|base)/).replaceAll('/persistence/trunk')
            text = (text =~ /http:\/\/anonsvn\.jboss\.org\/repos\/(weld|seam\/modules)\/([a-z]+)(\/[a-z]*)*/).replaceAll('git://github.com/seam/$2.git')
            text = (text =~ /https:\/\/svn\.jboss\.org\/repos\/(weld|seam\/modules)\/([a-z]+)(\/[a-z]*)*/).replaceAll('git@github.com:seam/$2.git')
            text = (text =~ /http:\/\/fisheye\.jboss\.org\/browse\/(([Ss]eam\/)+modules|weld)\/([a-z]+)(\/[a-z]*)*/).replaceAll('http://github.com/seam/$3')
            text = (text =~ /http:\/\/anonsvn\.jboss\.org\/repos\/seam\/([a-z]+\/)*(parent|examples|dist)(\/trunk(\/[a-z\-]*)?)?/).replaceAll('git://github.com/seam/$2.git')
            text = (text =~ /https:\/\/svn\.jboss\.org\/repos\/seam\/([a-z]+\/)*(parent|examples|dist)(\/trunk(\/[a-z\-]*)?)?/).replaceAll('git@github.com:seam/$2.git')
            text = text.replaceAll(/http:\/\/fisheye\.jboss\.org\/browse\/[Ss]eam(\/[a-z\-]*)*/, 'http://github.com/seam')
            text = text.replaceAll('http://anonsvn.jboss.org/repos/seam', 'http://github.com/seam')
            text = text.replaceAll('scm:svn', 'scm:git')
            return text
         })
         if (f.path.contains('/xml/')) {
            modifyFile(f, { text ->
               text = text.replaceAll(/github\.com(:|\/)seam\/xml/, 'github.com$1seam/xml-config')
               return text
            })
         }
         else if (f.path.contains('/remoting/')) {
            modifyFile(f, { text ->
               text = text.replaceAll(/github\.com(:|\/)seam\/remoting/, 'github.com$1seam/js-remoting')
               return text
            })
         }
         else if (f.path.contains('/catch/')) {
            modifyFile(f, { text ->
               text = text.replaceAll('LightGuard/seam-exception-handling', 'seam/catch')
               text = text.replaceAll('/tree/master', '')
               return text
            })
         }
      }
   })
}

def commit_and_push(rootDir) {
   rootDir.eachDir({ d ->
      cd(d.name)
      git('commit', '-a', '-m', 'update scm urls to reference git rather than svn') >> stdout
      //git('push', 'github', 'master') >> stdout
      cd('..')
   })
}
