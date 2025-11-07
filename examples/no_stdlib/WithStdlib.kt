package examples.no_stdlib

// This class compiles with stdlib automatically linked (default behavior)
class WithStdlib {
    fun greet(name: String): String {
        return "Hello, $name!"
    }
}
