const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const PROJECT_ID = "demo-no-project";
let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      host: "127.0.0.1",
      port: 8085,
      rules: fs.readFileSync("firestore.rules", "utf8"),
    },
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

test("unauthenticated user cannot read devices", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("devices").doc("dev1").get());
});

test("authenticated user can read and register device", async () => {
  const user1Db = testEnv.authenticatedContext("user1").firestore();
  await assertSucceeds(
    user1Db.collection("devices").doc("dev1").set({
      deviceId: "dev1",
      userId: "user1",
      deviceName: "Pixel 8",
      role: "MONITOR",
    })
  );
  await assertSucceeds(user1Db.collection("devices").doc("dev1").get());
});

test("authenticated user cannot create device with mismatched userId", async () => {
  const user1Db = testEnv.authenticatedContext("user1").firestore();
  await assertFails(
    user1Db.collection("devices").doc("dev2").set({
      deviceId: "dev2",
      userId: "user2",
      deviceName: "Galaxy S24",
      role: "DEVICE",
    })
  );
});

test("authenticated user can create pairing code and other auth user can read it", async () => {
  const user1Db = testEnv.authenticatedContext("user1").firestore();
  const user2Db = testEnv.authenticatedContext("user2").firestore();

  await assertSucceeds(
    user1Db.collection("pairing_codes").doc("123456").set({
      code: "123456",
      monitorDeviceId: "dev1",
      monitorUserId: "user1",
      status: "PENDING",
    })
  );

  await assertSucceeds(user2Db.collection("pairing_codes").doc("123456").get());
});
