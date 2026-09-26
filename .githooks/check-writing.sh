#!/bin/sh
# Checks text on stdin against docs/banned-words.txt, emoji and the em dash.
# The matching lives in check-writing.pl, next to this file, so no regex passes through shell quoting.
exec perl "$(dirname "$0")/check-writing.pl" "$@"
