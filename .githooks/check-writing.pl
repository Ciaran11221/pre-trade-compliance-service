#!/usr/bin/perl
# Reads text on stdin and prints "line: word" for each banned word, emoji or em dash.
# Exits 1 on any hit, 2 if the word list is missing. Same matching rule as WritingCheckTest:
# case-insensitive, at a word start, letters may follow ("robustness" hits), any whitespace
# between the words of a multi-word entry, straight and curly apostrophes treated the same.
use strict;
use warnings;
use utf8;
use File::Basename qw(dirname);

binmode(STDIN, ':encoding(UTF-8)');
binmode(STDOUT, ':encoding(UTF-8)');

my $list = shift @ARGV // dirname(__FILE__) . '/../docs/banned-words.txt';
open(my $fh, '<:encoding(UTF-8)', $list) or do {
    print STDERR "writing check: cannot read $list: $!\n";
    exit 2;
};

my @patterns;
while (my $entry = <$fh>) {
    $entry =~ s/^\s+|\s+$//g;
    next if $entry eq '' || $entry =~ /^#/;
    my $pattern = join '\s+', map {
        join "['\x{2019}]", map { quotemeta } split /['\x{2019}]/, $_, -1
    } split /\s+/, $entry;
    push @patterns, [$entry, qr/\b$pattern/i];
}
close $fh;

my $hits = 0;
while (my $line = <STDIN>) {
    for my $p (@patterns) {
        if ($line =~ $p->[1]) { print "$.: $p->[0]\n"; $hits++; }
    }
    if ($line =~ /[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{1F000}-\x{1F2FF}]/) { print "$.: [emoji]\n"; $hits++; }
    if ($line =~ /\x{2014}/) { print "$.: [em dash]\n"; $hits++; }
}
exit($hits ? 1 : 0);
