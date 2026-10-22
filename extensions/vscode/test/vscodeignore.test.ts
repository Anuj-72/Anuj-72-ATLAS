// What the extension ships is decided by .vscodeignore, not by .gitignore:
// `npm run test:coverage` (the script CI runs) writes its report — and its
// junit.xml — under coverage/, which .gitignore already ignores, so without an
// entry here `vsce package` put the whole report inside the VSIX.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const rules = readFileSync(join(__dirname, '..', '.vscodeignore'), 'utf8')
	.split('\n')
	.map((line) => line.trim())
	.filter((line) => line !== '' && !line.startsWith('#'));

// `coverage`, `coverage/` and `coverage/**` all exclude the directory
// wholesale; a rule that names a file inside it does not.
const excluded = rules.flatMap((rule) => [rule, rule.replace(/\/$/, ''), rule.replace(/\/\*\*$/, '')]);

describe('the packaged VSIX', () => {
	it('leaves out the coverage report test:coverage writes', () => {
		expect(excluded).toContain('coverage');
	});

	it('keeps the bundle package.json main points at', () => {
		expect(excluded.some((rule) => rule === 'dist' || rule.startsWith('dist/'))).toBe(false);
	});
});
