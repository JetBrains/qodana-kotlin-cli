class Greeter:
    # PyMethodMayBeStatic: a method that never uses `self`.
    def shout(self, text):
        return text.upper()


def describe():
    # PyUnusedLocal: `unused` is assigned but never read.
    unused = 42
    greeter = Greeter()
    return greeter.shout("qodana")
