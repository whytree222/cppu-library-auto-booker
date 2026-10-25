import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { releaseConfig } from './version.mjs';

export { releaseConfig };

if (process.argv[1] &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    console.log(JSON.stringify(releaseConfig(), null, 2));
  } catch (error) {
    console.error(`release-config: ${error.message}`);
    process.exit(2);
  }
}
