import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import QRCode from 'qrcode';
import { requireGitee } from './version.mjs';

const QR_OPTIONS = {
  type: 'png',
  margin: 2,
  errorCorrectionLevel: 'M',
  color: { dark: '#111111', light: '#FFFFFF' }
};

function githubOutput(out) {
  const target = path.parse(out);
  return path.join(target.dir, `${target.name}-github${target.ext || '.png'}`);
}

async function writeQr(target, url, size) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  await QRCode.toFile(target, url, { ...QR_OPTIONS, width: size });
  const stat = fs.statSync(target);
  if (!stat.size) throw new Error('QR image is empty');
  return stat.size;
}

export async function makeDownloadQr({
  root = process.cwd(),
  out = 'docs/download-qr.png',
  githubOut,
  size = 1024
} = {}) {
  const config = requireGitee(root);
  if (!Number.isInteger(size) || size < 256 || size > 2048) {
    throw new Error('QR size must be an integer between 256 and 2048');
  }
  const primaryTarget = path.resolve(root, out);
  const fallbackTarget = path.resolve(root, githubOut ?? githubOutput(out));
  if (primaryTarget === fallbackTarget) throw new Error('QR output paths must differ');

  const codes = [];
  for (const [label, target, url] of [
    ['gitee', primaryTarget, config.giteeDownloadUrl],
    ['github', fallbackTarget, config.githubDownloadUrl]
  ]) {
    const bytes = await writeQr(target, url, size);
    codes.push({ label, target, url, size: bytes });
  }
  return { codes };
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
    for (const code of result.codes) {
      console.log(`QR (${code.label}) for ${code.url}`);
      console.log(`  saved: ${code.target} (${code.size} bytes)`);
    }
  } catch (error) {
    console.error(`make-qr: ${error.message}`);
    process.exit(2);
  }
}
