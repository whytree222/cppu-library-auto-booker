const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const catalog = JSON.parse(fs.readFileSync('app/src/main/assets/seat-maps.json','utf8'));
const checked = JSON.parse(fs.readFileSync('diagnostics/2026-10-23-six-room-times.json','utf8'));

test('all six freshly checked room tables exactly match the sanitized school evidence',()=>{
  assert.equal(checked.submittedReservations,false);
  assert.deepEqual(checked.rooms.map(r=>r.roomNo).sort((a,b)=>a-b),[21,22,23,24,26,28]);
  for(const row of checked.rooms){
    const local=catalog.rooms.find(r=>r.roomNo===row.roomNo);
    assert.equal(local.name,row.name);
    assert.equal(local.floor,row.floor);
    assert.equal(local.slotsDate,row.useDate);
    assert.equal(local.slots.length,7);
    assert.deepEqual(local.slots,row.slots);
  }
});

test('all seven rooms have individual dated local time tables without replacing seat layouts',()=>{
  const original=JSON.parse(fs.readFileSync('seat-maps/2026-10-21/maps.json','utf8'));
  assert.equal(catalog.rooms.length,7);
  for(const room of catalog.rooms){
    assert.equal(room.slotsDate,'2026-10-24');
    const before=original.rooms.find(r=>r.roomNo===room.roomNo);
    assert.deepEqual(room.rows,before.rows);
    assert.equal(room.seatCount,before.seatCount);
  }
  assert.equal(catalog.rooms.find(r=>r.roomNo===21).slots[4].end,'18:30:30');
  assert.equal(catalog.rooms.find(r=>r.roomNo===23).slots[0].end,'10:00:01');
  assert.equal(catalog.rooms.find(r=>r.roomNo===24).slots[0].end,'10:00:01');
  assert.equal(catalog.rooms.find(r=>r.roomNo===28).slots[5].end,'19:29:00');
});
