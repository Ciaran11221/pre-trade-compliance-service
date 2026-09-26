#!/bin/sh
# Check stdin for banned words and emoji/em-dash

perl - "$1" <<'PERL_EOF'
use utf8;
use strict;
use warnings;
binmode(STDIN, ":utf8");
binmode(STDOUT, ":utf8");

my $banned_file = shift @ARGV || "docs/banned-words.txt";

# Read banned words
my @words = ();
if (open my $fh, '<:utf8', $banned_file) {
    while (<$fh>) {
        chomp;
        next if /^#/;
        next if /^$/;
        push @words, $_;
    }
    close $fh;
}

my @violations;
my $line_num = 0;

while (<>) {
    $line_num++;
    my $line = $_;

    # Check for each banned word
    foreach my $word (@words) {
        # Escape regex special chars except spaces
        my $escaped = quotemeta($word);
        # Convert spaces to \s+ for flexible whitespace matching
        $escaped =~ s/\\ /\\s+/g;
        # Convert apostrophes to match both straight and curly quotes
        $escaped =~ s/\\'/[\x27\x{2019}]/g;

        # Match at word boundary, case-insensitive
        if ($line =~ /\b(?:$escaped)\b/i) {
            my $match = $&;
            push @violations, "$line_num: $match";
            last;
        }
    }

    # Check for emoji (U+1F300-U+1FAFF, U+2600-U+27BF, U+1F000-U+1F2FF)
    if ($line =~ /([\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{1F000}-\x{1F2FF}])/) {
        push @violations, "$line_num: [emoji]";
    }

    # Check for em dash (U+2014)
    if ($line =~ /(\x{2014})/) {
        push @violations, "$line_num: [em dash]";
    }
}

if (@violations) {
    foreach my $v (@violations) {
        print "$v\n";
    }
    exit 1;
}
exit 0;
PERL_EOF
