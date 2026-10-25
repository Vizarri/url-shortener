const form = document.querySelector("#shorten-form");
const urlInput = document.querySelector("#url");
const submitButton = document.querySelector("#submit-button");
const formError = document.querySelector("#form-error");
const formStatus = document.querySelector("#form-status");
const result = document.querySelector("#result");
const shortUrlInput = document.querySelector("#short-url");
const openLink = document.querySelector("#open-link");
const copyButton = document.querySelector("#copy-button");
const copyStatus = document.querySelector("#copy-status");
const REQUEST_TIMEOUT_MS = 10_000;
let submitting = false;

/** Clears feedback so a previous result cannot be mistaken for the current request. */
function clearFeedback() {
    formError.hidden = true;
    formError.textContent = "";
    urlInput.removeAttribute("aria-invalid");
    result.hidden = true;
    shortUrlInput.value = "";
    copyStatus.textContent = "";
    formStatus.textContent = "";
}

/** Shows a failed request while keeping the entered destination available for correction. */
function showError(message, invalidUrl = false) {
    formError.textContent = message;
    formError.hidden = false;
    if (invalidUrl) {
        urlInput.setAttribute("aria-invalid", "true");
    }
}

urlInput.addEventListener("input", clearFeedback);

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting) {
        return;
    }

    clearFeedback();
    submitting = true;
    submitButton.disabled = true;
    submitButton.textContent = "Shortening…";
    urlInput.readOnly = true;
    form.setAttribute("aria-busy", "true");
    formStatus.textContent = "Creating your short link.";

    // A stalled request must not leave the form disabled indefinitely.
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
        const response = await fetch(form.action, {
            method: "POST",
            headers: {"Content-Type": "application/json", "Accept": "application/json"},
            body: JSON.stringify({url: urlInput.value.trim()}),
            signal: controller.signal
        });
        const body = await response.json();
        if (!response.ok) {
            const message = response.status === 400 || response.status === 503
                ? body?.detail : null;
            showError(message || "We couldn't create your link. Please try again.", response.status === 400);
            formStatus.textContent = "";
            return;
        }

        if (!body?.shortUrl) {
            throw new Error("Unexpected link response.");
        }
        shortUrlInput.value = body.shortUrl;
        openLink.href = body.shortUrl;
        result.hidden = false;
        formStatus.textContent = "Your short link is ready.";
        shortUrlInput.focus();
        shortUrlInput.select();
    } catch {
        showError(controller.signal.aborted
            ? "The request timed out. Please try again."
            : "We couldn't create your link. Please try again.");
        formStatus.textContent = "";
    } finally {
        clearTimeout(timeoutId);
        submitting = false;
        submitButton.disabled = false;
        submitButton.textContent = "Shorten link";
        urlInput.readOnly = false;
        form.removeAttribute("aria-busy");
    }
});

/** Falls back to manual selection when clipboard access is unavailable or denied. */
copyButton.addEventListener("click", async () => {
    const shortUrl = shortUrlInput.value;
    copyButton.disabled = true;
    try {
        await navigator.clipboard.writeText(shortUrl);
        if (!result.hidden && shortUrlInput.value === shortUrl) {
            copyStatus.textContent = "Link copied.";
        }
    } catch {
        if (!result.hidden && shortUrlInput.value === shortUrl) {
            shortUrlInput.focus();
            shortUrlInput.select();
            copyStatus.textContent = "Select the link and press ⌘C or Ctrl+C to copy.";
        }
    } finally {
        copyButton.disabled = false;
    }
});
