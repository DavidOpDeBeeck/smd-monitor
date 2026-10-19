export function matchScore(text: string, query: string): number {
    const haystack = text.toLowerCase();
    const index = haystack.indexOf(query);
    if (index >= 0) return 100 - index - (haystack.length - query.length) * 0.1;
    let position = 0;
    for (const character of query) {
        position = haystack.indexOf(character, position);
        if (position < 0) return -1;
        position++;
    }
    return 10 - position * 0.05;
}
