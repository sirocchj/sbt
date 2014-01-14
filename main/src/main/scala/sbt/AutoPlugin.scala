package sbt

	import logic.{Atom, Clause, Clauses, Formula, Literal, Logic}
	import Def.Setting
	import Natures._

/** Marks a top-level object so that sbt will wildcard import it for .sbt files, `consoleProject`, and `set`. */
trait AutoImport

/**
An AutoPlugin defines a group of settings and the conditions that the settings are automatically added to a build (called "activation").
The `select` method defines the conditions,
  `provides` defines an identifier for the AutoPlugin,
  and the a method like `projectSettings` defines the settings to add.

Steps for plugin authors:
1. Determine the natures that, when present (or absent), activate the AutoPlugin.
2. Determine the settings/configurations to automatically inject when activated.
3. Define a new, unique identifying [[Nature]] (which is a wrapper around a String ID).

For example, the following will automatically add the settings in `projectSettings`
  to a project that has both the `Web` and `Javascript` natures enabled.  It will itself
  define the `MyStuff` nature.  This nature can be explicitly disabled by the user to
  prevent the plugin from activating.

    object MyPlugin extends AutoPlugin {
        def select = Web && Javascript
        def provides = MyStuff
        def projectSettings = Seq(...)
    }

Steps for users:
1. add dependencies on plugins as usual with addSbtPlugin
2. add Natures to Projects, which will automatically select the plugin settings to add for those Projects.

For example, given natures Web and Javascript (perhaps provided by plugins added with addSbtPlugin),

  <Project>.natures( Web && Javascript )

will activate `MyPlugin` defined above and have its settings automatically added.  If the user instead defines

  <Project>.natures( Web && Javascript && !MyStuff)

then the `MyPlugin` settings (and anything that activates when `MyStuff` is activated) will not be added.
*/
abstract class AutoPlugin
{
	/** This AutoPlugin will be activated for a project when the [[Natures]] matcher returned by this method matches that project's natures
   * AND the user does not explicitly exclude the Nature returned by `provides`.
	*
	* For example, if this method returns `Web && Javascript`, this plugin instance will only be added
	* if the `Web` and `Javascript` natures are enabled. */
	def select: Natures

	/** The unique [[Nature]] for this AutoPlugin instance.  This has two purposes:
	* 1. The user can explicitly disable this AutoPlugin.
	* 2. Other plugins can activate based on whether this AutoPlugin was activated.
	*/
	def provides: Nature

	/** The [[Configuration]]s to add to each project that activates this AutoPlugin.*/
	def projectConfigurations: Seq[Configuration] = Nil

	/** The [[Setting]]s to add in the scope of each project that activates this AutoPlugin. */
	def projectSettings: Seq[Setting[_]] = Nil

	/** The [[Setting]]s to add to the build scope for each project that activates this AutoPlugin.
	* The settings returned here are guaranteed to be added to a given build scope only once
	* regardless of how many projects for that build activate this AutoPlugin. */
	def buildSettings: Seq[Setting[_]] = Nil

	/** The [[Setting]]s to add to the global scope exactly once if any project activates this AutoPlugin. */
	def globalSettings: Seq[Setting[_]] = Nil

	// TODO?: def commands: Seq[Command]
}

/** An expression that matches `Nature`s. */
sealed trait Natures {
	def && (o: Basic): Natures
}

/** Represents a feature or conceptual group of settings.
* `label` is the unique ID for this nature. */
final case class Nature(label: String) extends Basic {
	/** Constructs a Natures matcher that excludes this Nature. */
	def unary_! : Basic = Exclude(this)
	override def toString = label
}

object Natures
{
	// TODO: allow multiple AutoPlugins to provide the same Nature?
	// TODO: translate error messages
	def compile(defined: List[AutoPlugin]): Natures => Seq[AutoPlugin] =
		if(defined.isEmpty)
			Types.const(Nil)
		else
		{
			val byAtom = defined.map(x => (Atom(x.provides.label), x)).toMap
			val clauses = Clauses( defined.map(d => asClause(d)) )
			requestedNatures => {
				val results = Logic.reduce(clauses, flatten(requestedNatures).toSet)
				results.ordered.flatMap(a => byAtom.get(a).toList)
			}
		}

	def empty: Natures = Empty
	private[sbt] final object Empty extends Natures {
		def &&(o: Basic): Natures = o
		override def toString = "<none>"
	}

	/** An included or excluded Nature.  TODO: better name than Basic. */
	sealed abstract class Basic extends Natures {
		def &&(o: Basic): Natures = And(this :: o :: Nil)
	}
	private[sbt] final case class Exclude(n: Nature) extends Basic  {
		def unary_! : Nature = n
		override def toString = s"!$n"
	}
	private[sbt] final case class And(natures: List[Basic]) extends Natures {
		def &&(o: Basic): Natures = And(o :: natures)
		override def toString = natures.mkString(", ")
	}
	private[sbt] def and(a: Natures, b: Natures) = b match {
		case Empty => a
		case And(ns) => (a /: ns)(_ && _)
		case b: Basic => a && b
	}

	private[sbt] def asClause(ap: AutoPlugin): Clause =
		Clause( convert(ap.select), Set(Atom(ap.provides.label)) )

	private[this] def flatten(n: Natures): Seq[Literal] = n match {
		case And(ns) => convertAll(ns)
		case b: Basic => convertBasic(b) :: Nil
		case Empty => Nil
	}

	private[this] def convert(n: Natures): Formula = n match {
		case And(ns) => convertAll(ns).reduce[Formula](_ && _)
		case b: Basic => convertBasic(b)
		case Empty => Formula.True
	}
	private[this] def convertBasic(b: Basic): Literal = b match {
		case Exclude(n) => !convertBasic(n)
		case Nature(s) => Atom(s)
	}
	private[this] def convertAll(ns: Seq[Basic]): Seq[Literal] = ns map convertBasic
}