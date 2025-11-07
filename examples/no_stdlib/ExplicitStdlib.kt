package examples.no_stdlib

// This class would be used with a toolchain that has link_stdlib=False
// and explicitly adds stdlib as a dependency
class ExplicitStdlib {
    fun greet(name: String): String {
        return "Hello, $name!"
    }
}
