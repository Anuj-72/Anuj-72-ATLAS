//go:build unix

package main

import "syscall"

// openNonBlock makes an open return at once rather than wait for something the
// file may never get. A named pipe opened for reading without it waits for a
// writer; with it the open succeeds at once on the empty pipe, and the kind
// check that follows is what refuses the read.
//
// The tag is unix rather than linux because the flag is the same constant on
// every platform this builds for: a session on a developer's Mac meets the
// same pipe in the same workspace as one in a container.
const openNonBlock = syscall.O_NONBLOCK
