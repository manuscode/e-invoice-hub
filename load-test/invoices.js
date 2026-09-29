import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const RUN_ID = requiredEnv('RUN_ID');
const HUB_URL = requiredEnv('HUB_URL');
const TOKEN_URL = requiredEnv('TOKEN_URL');

const STEADY_RATE = Number(__ENV.STEADY_RATE || 5);
const STEADY_DURATION = __ENV.STEADY_DURATION || '5m';
const PEAK_RATE = Number(__ENV.PEAK_RATE || 20);
// JIT and the first KoSIT checks make the first requests slow. They are not part of the steady numbers.
const WARM_UP_DURATION = '30s';

// Every sample has this invoice number. It is replaced, so each document is new for the hub.
const SAMPLE_INVOICE_NUMBER = '123456XX';
// Only changes the note, so the document has another hash but the same business key.
const SAMPLE_NOTE = 'Es gelten unsere';
const CHANGED_NOTE = 'Zweitschrift: Es gelten unsere';

const SAMPLES = {
    valid: {
        ubl: open('/samples/xrechnung-ubl-valid.xml'),
        cii: open('/samples/xrechnung-cii-valid.xml'),
    },
    invalid: {
        ubl: open('/samples/xrechnung-ubl-missing-buyer-reference.xml'),
        cii: open('/samples/xrechnung-cii-missing-buyer-reference.xml'),
    },
};

// Mix per 20 iterations: 14 valid, 3 invalid, 2 duplicates (same business key), 1 same document again.
const CASES = [
    ...Array(14).fill(sendValid),
    ...Array(3).fill(sendInvalid),
    ...Array(2).fill(sendDuplicate),
    sendSameDocumentAgain,
];

// Different documents only, the same document again is not counted. run.sh compares this with the database.
const documentsSent = new Counter('documents_sent');

export const options = {
    scenarios: {
        warmUp: {
            executor: 'constant-arrival-rate',
            rate: 2,
            timeUnit: '1s',
            duration: WARM_UP_DURATION,
            preAllocatedVUs: 10,
        },
        steady: {
            executor: 'constant-arrival-rate',
            startTime: WARM_UP_DURATION,
            rate: STEADY_RATE,
            timeUnit: '1s',
            duration: STEADY_DURATION,
            preAllocatedVUs: 20,
            maxVUs: 100,
        },
        // End of month: many suppliers send their invoices at the same time. The rate rises slowly, so the
        // report shows at which rate the latency breaks.
        peak: {
            executor: 'ramping-arrival-rate',
            startTime: `${durationInSeconds(WARM_UP_DURATION) + durationInSeconds(STEADY_DURATION)}s`,
            startRate: STEADY_RATE,
            timeUnit: '1s',
            stages: [
                { target: PEAK_RATE, duration: '3m' },
                { target: PEAK_RATE, duration: '1m' },
                { target: STEADY_RATE, duration: '30s' },
            ],
            preAllocatedVUs: 50,
            maxVUs: 300,
        },
    },
    thresholds: {
        'http_req_duration{endpoint:upload,scenario:steady}': ['p(95)<500', 'p(99)<1000'],
        'http_req_failed{endpoint:upload,scenario:steady}': ['rate==0'],
        'checks{scenario:steady}': ['rate==1'],
        // Only for the summary, the peak is expected to exceed the limit.
        'http_req_duration{endpoint:upload,scenario:peak}': ['max>=0'],
        'http_req_failed{endpoint:upload,scenario:peak}': ['rate>=0'],
        'checks{scenario:peak}': ['rate>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    // Fails fast if Keycloak is not ready, instead of failing every request.
    accessToken();
}

export default function () {
    const iteration = exec.scenario.iterationInTest;
    const format = iteration % 2 === 0 ? 'ubl' : 'cii';
    const invoiceNumber = `LT-${RUN_ID}-${exec.scenario.name}-${iteration}`;
    CASES[iteration % CASES.length](format, invoiceNumber);
}

function sendValid(format, invoiceNumber) {
    const response = upload(document(SAMPLES.valid[format], invoiceNumber), 'valid');
    check(response, { 'valid: 201 VALID': (r) => isStored(r, 'VALID') });
}

function sendInvalid(format, invoiceNumber) {
    const response = upload(document(SAMPLES.invalid[format], invoiceNumber), 'invalid');
    check(response, { 'invalid: 201 REJECTED': (r) => isStored(r, 'REJECTED') });
}

function sendDuplicate(format, invoiceNumber) {
    const original = upload(document(SAMPLES.valid[format], invoiceNumber), 'duplicate');
    const duplicate = upload(
        document(SAMPLES.valid[format].replace(SAMPLE_NOTE, CHANGED_NOTE), invoiceNumber, 'duplicate'),
        'duplicate');
    check(original, { 'duplicate: original 201 VALID': (r) => isStored(r, 'VALID') });
    check(duplicate, {
        'duplicate: 201 DUPLICATE of original': (r) =>
            isStored(r, 'DUPLICATE') && r.json('duplicateOf') === original.json('id'),
    });
}

function sendSameDocumentAgain(format, invoiceNumber) {
    const invoice = document(SAMPLES.valid[format], invoiceNumber);
    const first = upload(invoice, 'same-document');
    const second = post(invoice, 'same-document');
    check(first, { 'same document: first 201 VALID': (r) => isStored(r, 'VALID') });
    check(second, {
        'same document: second 200 with existing invoice': (r) =>
            r.status === 200 && r.json('id') === first.json('id'),
    });
}

function document(sample, invoiceNumber, suffix = 'original') {
    return {
        filename: `load-test-${RUN_ID}-${invoiceNumber}-${suffix}.xml`,
        content: sample.replace(SAMPLE_INVOICE_NUMBER, invoiceNumber),
    };
}

function upload(invoice, caseName) {
    documentsSent.add(1);
    return post(invoice, caseName);
}

function post(invoice, caseName) {
    return http.post(
        `${HUB_URL}/api/invoices`,
        { file: http.file(invoice.content, invoice.filename, 'application/xml') },
        {
            headers: { Authorization: `Bearer ${accessToken()}` },
            tags: { endpoint: 'upload', case: caseName },
        });
}

function isStored(response, expectedStatus) {
    return response.status === 201 && response.json('status') === expectedStatus;
}

// Tokens of the demo realm are valid for 5 minutes, shorter than the test. Each VU caches its own token.
let cachedToken = { value: null, expiresAt: 0 };

function accessToken() {
    if (Date.now() < cachedToken.expiresAt) {
        return cachedToken.value;
    }
    const response = http.post(
        TOKEN_URL,
        { grant_type: 'client_credentials', client_id: 'demo-uploader', client_secret: 'demo-uploader-secret' },
        { tags: { endpoint: 'token' } });
    if (response.status !== 200) {
        throw new Error(`Token request failed with status ${response.status}: ${response.body}`);
    }
    // Renewed 30 seconds early, so a token doesn't expire during a slow request.
    cachedToken = {
        value: response.json('access_token'),
        expiresAt: Date.now() + (response.json('expires_in') - 30) * 1000,
    };
    return cachedToken.value;
}

// k6 has no arithmetic for durations. Only seconds and minutes are used here.
function durationInSeconds(duration) {
    const match = /^(\d+)(s|m)$/.exec(duration);
    if (!match) {
        throw new Error(`Unsupported duration ${duration}, use e.g. 30s or 5m`);
    }
    return Number(match[1]) * (match[2] === 'm' ? 60 : 1);
}

function requiredEnv(name) {
    const value = __ENV[name];
    if (!value) {
        throw new Error(`Environment variable ${name} is not set`);
    }
    return value;
}
