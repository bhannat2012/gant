//  Gant -- A Groovy build framework based on scripting Ant tasks.
//
//  Copyright © 2006-7 Russel Winder
//
//  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
//  compliance with the License. You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software distributed under the License is
//  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
//  implied. See the License for the specific language governing permissions and limitations under the
//  License.

package gant

import java.lang.reflect.InvocationTargetException

import org.codehaus.groovy.control.MultipleCompilationErrorsException    
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.CompilationUnit

import org.codehaus.gant.GantBuilder
import org.codehaus.gant.GantMetaClass
import org.codehaus.gant.GantState
import org.codehaus.gant.IncludeTargets
import org.codehaus.gant.IncludeTool

import org.apache.commons.cli.GnuParser
import org.apache.commons.cli.Option
import org.apache.commons.cli.OptionBuilder

/**
 *  This class provides infrastructure and an executable command for using Groovy + AntBuilder as a build
 *  tool in a way similar to Rake and SCons.  However, where Rake and SCons are dependency programming
 *  systems based on Ruby and Python respectively, Gant is simply a way of scripting Ant tasks; the Ant
 *  tasks do all the dependency management.
 *
 *  <p>A Gant build specification file (default name build.gant) is assumed to contain one or more targets.
 *  Dependencies between targets are handled as function calls within functions, or by use of the depends
 *  function. Execution of Ant tasks is by calling methods on the object called `Ant', which is predefined
 *  as an <code>GantBuilder</code> instance.</p>
 *
 *  <p>On execution of the gant command, the Gant build specification is read and executed in the context of
 *  a predefined binding.  An object called `Ant' is part of the binding so methods can use this object to
 *  get access to the Ant tasks without having to create an object explicitly.  A method called `target' is
 *  part of the predefined binding.  A target has two parameters, a single item map and a closure.  The
 *  single item map has the target name as the key and the documentation about the target as the value.
 *  This documentation is used by the `gant -T' / `gant --targets' / `gant -p' command to present a list of
 *  all the documented targets.</p>
 *
 *  <p>NB In the following example some extra spaces have had to be introduced because some of the patterns
 *  look like comment ends:-(</p>
 * 
 *  <p>A trivial example build specification is:</p>
 *
 *  <pre>
 *      target ( 'default' : 'The default target.' ) {
 *        clean ( )
 *        otherStuff ( )
 *      }
 *      target ( otherStuff : 'Other stuff' ) {
 *        depends ( clean )
 *      }
 *      target ( clean : 'Clean the directory and subdirectories' ) {
 *        delete ( dir : 'build' , quiet : 'true' )
 *        delete ( quiet : 'true' ) { fileset ( dir : '.' , includes : '** /*~,** /*.bak'  , defaultexcludes : 'false' ) }
 *      }
 * </pre>
 *
 *  <p>or, using some a ready made targets class:</p>
 *
 *  <pre>
 *      includeTargets << gant.targets.Clean
 *      cleanPattern << [ '** / *~' , '** / *.bak' ]
 *      cleanDirectory << 'build'
 *      target ( 'default' : 'The default target.' ) {
 *        clean ( )
 *        otherStuff ( )
 *      }
 *      target ( otherStuff : 'Other stuff' ) {
 *        depends ( clean )
 *      }
 *
 *  <p><em>Note that there is an space between the two asterisks and the solidus in the fileset line that
 *  should notbe there, we have to have it in the source because asterisk followed by solidus is end of
 *  comment in Groovy</em></p>
 *
 *  @author Russel Winder <russel.winder@concertant.com>
 *  @author Graeme Rocher <graeme.rocher@gmail.com>
 */
final class Gant {
  private buildFileName = 'build.gant'
  private buildClassName = buildFileName.replaceAll ( '\\.' , '_' ) 
  private final targetDescriptions = new TreeMap ( ) 
  private final binding = new Binding ( )
  private final groovyShell = new GroovyShell ( binding )
  private final classLoader = getClass().getClassLoader()
  private final target = { Map map , Closure closure ->
    def targetName = map.keySet ( ).iterator ( ).next ( )
    def targetDescription = map.get ( targetName )
    if ( targetDescription ) { targetDescriptions.put ( targetName , targetDescription ) }
    closure.metaClass = new GantMetaClass ( closure.class , binding )
    binding.setVariable ( targetName , closure )
    binding.setVariable ( targetName + '_description' , targetDescription )
  }
  private final message = { String tag , String message ->
    def padding = 9 - tag.length ( )
    if ( padding < 0 ) { padding = 0 }
    println ( "           ".substring ( 0 , padding ) + '[' + tag + '] ' + message )
  }
  private final setdefault = { defaultTarget -> // Deal with Closure or String arguments.
    switch ( defaultTarget.getClass ( ) ) {
     case Closure :
     def targetName = null
     binding.variables.each { key , value -> if ( value.is ( defaultTarget ) ) { targetName = key } }
     if ( targetName == null ) { throw new RuntimeException ( 'Parameter to setdefault method is not a known target.  This can never happen!' ) }
     target ( 'default' : targetName ) { defaultTarget ( ) }
     break
     case String :
     def failed = true
     try {
       def targetClosure = binding.getVariable ( defaultTarget )
       if ( targetClosure != null ) { target ( 'default' : defaultTarget ) { targetClosure ( ) } ; failed = false }
     }
     catch ( MissingPropertyException mpe ) { }
     if ( failed ) { throw new RuntimeException ( "Target ${defaultTarget} does not exist so cannot be made the default." ) }
     break
     default :
     throw new RuntimeException ( 'Parameter to setdefault is of the wrong type -- must be a target reference or a string.' )
     break 
    }
  }
  private def ant = new GantBuilder ( ) ; {
    //
    //  To avoid using System.getenv which is deprecated on 1.4, we use Ant to access the environment.  Can
    //  remove this once Groovy depends on 1.5.
    //
    ant.property ( environment : 'environment' )
  }
  private List gantLib ; {
    // def item = System.getenv ( ).GANTLIB ;
    def item = ant.project.properties.'environment.GANTLIB'
    if ( item == null ) { gantLib = [ ] }
    else { gantLib = Arrays.asList ( item.split ( System.properties.'path.separator' ) ) }
  }
  public Gant ( ) { this ( null , getClass ( ).getClassLoader ( ) ) }  
  public Gant ( Binding b ) { this ( b , getClass ( ).getClassLoader ( ) ) }
  public Gant ( Binding b , ClassLoader cl ) {
    if ( b ) { this.binding = b }
    if ( cl ) { this.classLoader = cl }
    this.groovyShell = new GroovyShell ( this.classLoader , this.binding ) // Appears to assign a final variable :-( cf GROOVY-2302.
    binding.gantLib = gantLib
    binding.Ant = ant
    binding.groovyShell = groovyShell
    binding.includeTargets = new IncludeTargets ( binding )
    binding.includeTool = new IncludeTool ( binding )
    binding.target = target
    binding.task = { Map map , Closure closure -> System.err.println ( 'Deprecation warning: Use of task instead of target is deprecated.' ) ; target ( map , closure ) }
    binding.message = message
    binding.setdefault = setdefault
  }
  private int targetList ( targets ) {
    def max = 0
    targetDescriptions.entrySet ( ).each { item ->
      if ( item.key != 'default' ) {
        def size = item.key.size ( )
        if ( size > max ) { max = size }
      }
    }
    println ( )
    targetDescriptions.entrySet ( ).each { item ->
      if ( item.key != 'default' ) {
        println ( ' ' + item.key + ' ' * ( max - item.key.size ( ) ) + '  ' + item.value )
      }
    }
    println ( )
    def message = targetDescriptions [ 'default' ]
    if ( message != null ) { println ( 'Default target is ' + message + '.' ) ; println ( ) }
    0
  }
  private void printDispatchExceptionMessage ( target , method , message ) {
    println ( ( target == method ) ? "Target ${method} does not exist." : "Could not execute method ${method}.\n${message}" )
  }
  private int dispatch ( targets ) {
    def returnCode = 0
    try {
      if ( targets.size ( ) > 0 ) {
        targets.each { target ->
          try { binding.getVariable ( target ).run ( ) }
          catch ( MissingPropertyException mme ) {
            printDispatchExceptionMessage ( target , mme.property , mme.message )
            returnCode = 11
          }
        }
      }
      else {
        try { binding.getVariable ( 'default' ).run ( ) }
        catch ( MissingPropertyException mme ) {
          printDispatchExceptionMessage ( 'default' , mme.property , mme.message )
          returnCode = 12
        }
      }
    }
    catch ( Exception e ) {
      println ( e.message )
      returnCode = 13
    }
    returnCode
  }
  public int process ( args ) {
    final rootLoader = classLoader.rootLoader
    //
    //  Commons CLI is broken.  1.0 has one set of ideas about multiple args and is broken.  1.1 has a
    //  different set of ideas about multiple args and is broken.  For the moment we leave things so that
    //  they work in 1.0.
    //
    //  1.0 silently absorbs unknown single letter options.
    //
    //  1.0 cannot deal with options having only a long form as the access mechanism that works only works
    //  for short form.
    //
    //  The PosixParser does not handle incorrectly formed options at all well.  Also the standard printout
    //  actually assumes GnuParser form.  So although PosixParser is the default for CliBuilder, we actually
    //  want GnuParser.
    //
    def cli = new CliBuilder ( usage : 'gant [option]* [target]*' , parser : new GnuParser ( ) )
    cli.c ( longOpt : 'usecache' , 'Whether to cache the generated class and perform modified checks on the file before re-compilation.' )
    cli.d ( longOpt : 'cachedir' , args : 1 , argName : 'cache-file' , 'The directory where to cache generated classes to.' )
    cli.f ( longOpt : 'gantfile' , args : 1 , argName : 'build-file' , 'Use the named build file instead of the default, build.gant.' )
    cli.h ( longOpt : 'help' , 'Print out this message.' )
    //  This options should have "args : Option.UNLIMITED_VALUES" but that doesn't work.
    cli.l ( longOpt : 'gantlib' , args : 1 , argName : 'library' , 'A directory that contains classes to be used as extra Gant modules,' )
    cli.n ( longOpt : 'dry-run' , 'Do not actually action any tasks.' )
    cli.p ( longOpt : 'projecthelp' , 'Print out a list of the possible targets.' )
    cli.q ( longOpt : 'quiet' , 'Do not print out much when executing.' )
    cli.s ( longOpt : 'silent' , 'Print out nothing when executing.' )
    cli.v ( longOpt : 'verbose' , 'Print lots of extra information.' )
    //  This options should have "args : Option.UNLIMITED_VALUES" but that doesn't work.
    cli.D (argName : 'name>=<value' , args : 1 , 'Define <name> to have value <value>.  Creates a variable named <name> for use in the scripts and a property named <name> for the Ant tasks.' )
    cli.P ( longOpt : 'classpath' , args : 1 , argName : 'path' , 'Adds a path to search for jars and classes.' )
    cli.T ( longOpt : 'targets' , 'Print out a list of the possible targets.' )
    cli.V ( longOpt : 'version' , 'Print the version number and exit.' )
    def options = cli.parse ( args )
    if ( options == null ) { println ( 'Error in processing command line options.' ) ; return 1 }
    binding.cacheEnabled = options.c ? true : false
    binding.cacheDirectory = binding.cacheEnabled && options.d ? new File ( options.d ) : new File ( "${System.properties.'user.home'}/.gant/cache" )
    if ( options.f ) {
      buildFileName = options.f
      buildClassName = buildFileName.replaceAll ( '\\.' , '_' ) 
    }
    if ( options.h ) { cli.usage ( ) ; return 0 }
    if ( options.l ) { gantLib = options.l.split ( System.properties.'path.separator' ) }
    if ( options.n ) { GantState.dryRun = true }
    def function =  ( options.p || options.T ) ? 'targetList' : 'dispatch'
    if ( options.q ) { GantState.verbosity = GantState.QUIET }
    if ( options.s ) { GantState.verbosity = GantState.SILENT }
    if ( options.v ) { GantState.verbosity = GantState.VERBOSE }
    if ( options.D ) {
      options.Ds.each { definition ->
        def pair = definition.split ( '=' ) as List
        if ( pair.size ( ) < 2 ) { pair << '' }
        binding.Ant.property ( name : pair[0] , value : pair[1] )
        binding.setVariable ( pair[0] , pair[1] )
      }
    }
    if ( options.P ) { options.P.split ( System.properties.'path.separator' ).each { pathitem -> rootLoader?.addURL ( ( new File ( pathitem ) ).toURL ( ) ) } }
    if ( options.V ) {
      def version = ''
      final gantPackage = Package.getPackage ( 'gant' )
      if ( gantPackage != null ) { version = gantPackage.getImplementationVersion ( ) }
      println ( 'Gant version ' + ( ( version == null ) ? '<unknown>' : version ) )
      return 0
    }
    //  The rest of the arguments appear to be delivered as a single string as the first item in a list.  This is surely an error but
    //  with Commons CLI 1.0 it is the case.  So we must partition.  NB the split method delivers an array
    //  of Strings so we cast to a List.
    def targets = options.arguments ( )
    if ( ( targets != null ) && ( targets.size ( ) == 1 ) ) { targets = targets[ 0 ].split ( ' ' ) as List }
    def gotUnknownOptions = false ;
    targets.each { target ->
      if ( target[0] == '-' ) {
        println ( 'Unknown option: ' + target ) 
        gotUnknownOptions = true
      }
    }
    if ( gotUnknownOptions ) { cli.usage ( ) ; return 1 ; }
    def userAntLib = new File ( "${System.properties.'user.home'}/.ant/lib" )
    if ( userAntLib.isDirectory ( ) ) { userAntLib.eachFile { file -> rootLoader?.addURL ( file.toURL ( ) ) } }
    //def antHome = System.getenv ( ).'ANT_HOME'
    def antHome = ant.project.properties.'environment.ANT_HOME'
    if ( ( antHome != null ) && ( antHome != '' ) ) {
      def antLib = new File ( antHome + '/lib' )
      if ( antLib.isDirectory ( ) ) { antLib.eachFileMatch ( ~/ant-.*\.jar/ ) { file -> rootLoader?.addURL ( file.toURL ( ) ) } }
    }
    def buildFileText = ''
    def buildFileModified = -1  
    def buildFile = null
    def standardInputClassName = 'standard_input'
    if ( buildFileName == '-' ) {
      buildFileText = System.in.text
      buildClassName = standardInputClassName
    }
    else {
      buildFile = new File ( buildFileName ) 
      if ( ! buildFile.isFile ( ) ) { println ( 'Cannot open file ' + buildFileName ) ; return 1 }
      buildClassName = buildFile.name.replaceAll ( /\./ , '_' )
      buildFileModified = buildFile.lastModified ( )
    }
    if ( binding.cacheEnabled ) {       
      if ( buildFile == null ) { println 'Caching can only be used in combination with the -f option.' ; return 1 }
      def cacheDirectory = binding.cacheDirectory
      if ( classLoader instanceof URLClassLoader ) { classLoader.addURL ( cacheDirectory.toURL ( ) ) }
      else { rootLoader?.addURL ( cacheDirectory.toURL ( ) ) }      
      def loadClassFromCache = { className , fileLastModified , file  ->
        try {      
          def url = classLoader.getResource ( "${className}.class" )
          if ( url ) {
            if ( fileLastModified > url.openConnection ( ).lastModified ) {
              compileScript ( cacheDirectory , file.text , className )
            }
          }
          def script = classLoader.loadClass ( className ).newInstance ( )
          script.binding = binding
          script.run ( )
        }
        catch ( Exception e) {
          def fileText = file.text
          compileScript ( cacheDirectory , fileText , className )
          groovyShell.evaluate ( fileText , className )			
        }
      }
      binding.loadClassFromCache =  loadClassFromCache
      loadClassFromCache ( buildClassName , buildFileModified , buildFile )
    }
    else {
      if ( buildFile )  { buildFileText = buildFile.text }
      try { groovyShell.evaluate ( buildFileText , buildClassName ) }
      catch ( Exception e ) {
        for ( stackEntry in e.stackTrace ) {
          if ( stackEntry.fileName == buildClassName ) {
            def sourceName = ( buildClassName == standardInputClassName ) ? 'Standard input' : buildFile.name
            print ( sourceName + ', line ' + stackEntry.lineNumber + ' -- ' )
          }
        }
        println ( 'Error evaluating Gantfile: ' + ( e instanceof InvocationTargetException ? e.cause.message : e.message  ) )
        return 2
      }
    }
    invokeMethod ( function , targets )
  }
  private void compileScript ( destDir , buildFileText , buildClassName ) {
    if ( ! destDir.exists ( ) ) { destDir.mkdirs ( ) }
    def configuration = new CompilerConfiguration ( )
    configuration.setTargetDirectory ( destDir )
    def unit = new CompilationUnit ( configuration , null , new GroovyClassLoader ( classLoader ) )
    unit.addSource ( buildClassName , new ByteArrayInputStream ( buildFileText.bytes ) )
    unit.compile ( )				
  }
  public static main ( args ) { System.exit ( ( new Gant ( ) ).process ( args ) ) }
}