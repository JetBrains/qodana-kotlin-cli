// Go inspection planted defects for the qodana-go e2e.
package main

import (
	"errors"
	"fmt"
)

// GoErrorStringFormat: a Go error string must not be capitalized or end with punctuation.
func openThing() error {
	return errors.New("Failed to open the thing")
}

// GoUnusedFunction: this unexported helper is never called from anywhere.
func unusedHelper() string {
	return "never referenced"
}

func main() {
	if err := openThing(); err != nil {
		fmt.Println(err)
	}
}
