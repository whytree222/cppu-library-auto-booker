import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import QRCode from 'qrcode';
import { requireGitee } from './version.mjs';

export async function makeDownloadQr({
  root = process.cwd(),
  out = 'docs/download-qr.png',
  size = 1024
} = {}) {
  const config = requireGitee(root);
  if (!Number.isInteger(size) || size < 256 || size > 2048) {
    throw new Error('QR size must be an integer between 256 and 2048');
  }
  const target = path.resolve(root, out);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  await QRCode.toFile(target, config.giteeReleaseUrl, {
    type: 'png',
    width: size,
    margin: 2,
    errorCorrectionLevel: 'M',
    color: { dark: '#111111', light: '#FFFFFF' }
  });
  const stat = fs.statSync(target);
  if (!stat.size) throw new Error('QR image is empty');
  return { target, url: config.giteeReleaseUrl, size: stat.size };
}

function parseArgs(argv) {
  const options = {};
  while (argv.length) {
    const arg = argv.shift();
    if (arg === '--out' || arg === '--size') {
      const value = argv.shift();
      if (!value) throw new Error(`${arg} requires a value`);
      options[arg.slice(2)] = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return {
    out: options.out ?? 'docs/download-qr.png',
    size: options.size === undefined ? 1024 : Number(options.size)
  };
}

function isMain() {
  return Boolean(process.argv[1]) &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
}

if (isMain()) {
  try {
    const result = await makeDownloadQr(parseArgs(process.argv.slice(2)));
    console.log(`QR for ${result.url}`);
    console.log(`  saved: ${result.target} (${result.size} bytes)`);
  } catch (error) {
    console.error(`make-qr: ${error.message}`);
    process.exit(2);
  }
}
