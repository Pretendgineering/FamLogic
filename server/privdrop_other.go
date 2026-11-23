//go:build !linux

package main

// dropPrivileges only does something in the Linux container (see privdrop_linux.go).
func dropPrivileges(dataDir, vaultDir string) error { return nil }
