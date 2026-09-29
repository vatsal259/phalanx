let port = null;

function connect() {
  if (port) {
    return;
  }
  port = chrome.runtime.connectNative("com.phalanx.broker");
  port.onMessage.addListener(onFill);
  port.onDisconnect.addListener(() => {
    const message = chrome.runtime.lastError && chrome.runtime.lastError.message;
    port = null;
    if (message) {
      console.error(message);
    }
    setTimeout(connect, 500);
  });
}

function samePage(tabUrl, site) {
  return tabUrl === site || (tabUrl && site && tabUrl.startsWith(site));
}

async function onFill(message) {
  if (!message || message.type !== "fill") {
    return;
  }
  let status = "fill_failed";
  try {
    const tabs = await chrome.tabs.query({ url: "http://localhost:4711/*" });
    const tab = tabs.find((candidate) => samePage(candidate.url, message.site));
    if (!tab || !message.site || !message.site.startsWith("http://localhost:4711/")) {
      status = "url_mismatch";
    } else {
      try {
        await chrome.scripting.executeScript({
          target: { tabId: tab.id },
          func: (username, password) => {
            const form = document.querySelector("form");
            const user = form && form.querySelector("input[name=username]");
            const pass = form && form.querySelector("input[name=password]");
            if (!user || !pass) {
              return;
            }
            user.value = username;
            pass.value = password;
            form.submit();
          },
          args: [message.username, message.password]
        });
      } catch (error) {
        // Submitting the form navigates the tab and can end this call.
      }
      status = await waitForSignedIn(tab.id);
    }
  } catch (error) {
    status = "fill_failed";
  }
  if (port) {
    port.postMessage({ type: "result", status });
  }
}

function waitForSignedIn(tabId) {
  const deadline = Date.now() + 8000;
  return new Promise((resolve) => {
    const timer = setInterval(async () => {
      if (Date.now() > deadline) {
        clearInterval(timer);
        await clearPassword(tabId);
        resolve("fill_failed");
        return;
      }
      try {
        const injected = await chrome.scripting.executeScript({
          target: { tabId },
          func: () => {
            const heading = document.querySelector("h1");
            return heading ? heading.textContent : "";
          }
        });
        const text = injected[0] && injected[0].result;
        if (typeof text === "string" && text.startsWith("Signed in")) {
          clearInterval(timer);
          resolve("success");
        }
      } catch (error) {
        // The form submit is still navigating.
      }
    }, 200);
  });
}

async function clearPassword(tabId) {
  try {
    await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        const pass = document.querySelector("input[name=password]");
        if (pass) {
          pass.value = "";
        }
      }
    });
  } catch (error) {
    // The tab has already left the form.
  }
}

connect();
chrome.runtime.onInstalled.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
