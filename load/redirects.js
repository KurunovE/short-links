import http from 'k6/http';
import {check} from 'k6';
import {Counter, Rate, Trend} from 'k6/metrics';

const MAX_DATASET_SIZE = 100000;
const MAX_RUN_ID_LENGTH = 12;
const TARGET_P95_MS = 20;
const HTTP_CREATED = 201;
const HTTP_CONFLICT = 409;
const HTTP_REDIRECT = 302;
const RUN_ID_PATTERN = new RegExp(`^[a-z0-9]{1,${MAX_RUN_ID_LENGTH}}$`);

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const runId = __ENV.RUN_ID;
if (!runId || !RUN_ID_PATTERN.test(runId)) {
  throw new Error('RUN_ID must be 1–12 lowercase alphanumeric characters');
}
const size = Number(__ENV.DATASET_SIZE || 1000);
const warmup = Number(__ENV.WARMUP_SECONDS || 15);
const duration = Number(__ENV.DURATION_SECONDS || 60);
const vus = Number(__ENV.VUS || 20);
if (
  ![size, warmup, duration, vus].every(Number.isInteger) ||
  size < 1 || size > MAX_DATASET_SIZE || warmup < 1 || duration < 1 || vus < 1
) {
  throw new Error('Invalid load parameters');
}

const errors = new Rate('redirect_errors');
const latency = new Trend('redirect_latency', true);
const requests = new Counter('redirect_requests');

export const options = {
  maxRedirects: 0,
  setupTimeout: '5m',
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      exec: 'redirect',
      vus,
      duration: `${warmup}s`,
      gracefulStop: '0s',
      tags: {phase: 'warmup'},
    },
    measured: {
      executor: 'constant-vus',
      exec: 'redirect',
      vus,
      duration: `${duration}s`,
      startTime: `${warmup}s`,
      gracefulStop: '5s',
      tags: {phase: 'measured'},
    },
  },
  thresholds: {
    'redirect_requests{phase:measured}': ['count>0'],
    'redirect_errors{phase:measured}': ['rate==0'],
    'redirect_latency{phase:measured}': [`p(95)<${TARGET_P95_MS}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  for (let i = 0; i < size; i++) {
    const alias = `load_${runId}_${i}`;
    const url = `https://example.invalid/load/${runId}/${i}`;
    const response = http.post(`${base}/api/links`, JSON.stringify({url, alias}), {
      headers: {'Content-Type': 'application/json'},
      tags: {name: 'prepare'},
      timeout: '10s',
    });
    if (response.status !== HTTP_CREATED && response.status !== HTTP_CONFLICT) {
      throw new Error(`Preparation failed: ${response.status}`);
    }
    const probe = http.get(`${base}/${alias}`, {
      redirects: 0,
      tags: {name: 'prepare_probe'},
      timeout: '10s',
    });
    if (probe.status !== HTTP_REDIRECT || probe.headers.Location !== url) {
      throw new Error('Existing dataset differs from expected targets');
    }
  }
}

export function redirect() {
  // Uniform distribution over the same dataset in both cache configurations.
  const index = Math.floor(Math.random() * size);
  const response = http.get(`${base}/load_${runId}_${index}`, {
    redirects: 0,
    tags: {name: 'GET /{code}'},
    timeout: '5s',
  });
  const ok = check(response, {
    '302 with expected target and no-store': r =>
      r.status === HTTP_REDIRECT &&
      r.headers.Location === `https://example.invalid/load/${runId}/${index}` &&
      r.headers['Cache-Control'] === 'no-store',
  });
  errors.add(!ok);
  latency.add(response.timings.duration);
  requests.add(1);
}

export function handleSummary(data) {
  const name = __ENV.SUMMARY_FILE || 'target/load-summary.json';
  return {
    [name]: JSON.stringify({
      runId,
      size,
      warmup,
      duration,
      vus,
      distribution: 'uniform',
      cacheEnabled: __ENV.CACHE_ENABLED,
      results: data,
    }, null, 2),
  };
}
