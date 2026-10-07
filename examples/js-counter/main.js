// Counter: an interface drawn by the app, events coming back to the script.
var count = Number(mf.storage.read("count.txt") || 0);

function draw() {
    mf.ui.show([
        { type: "text", text: "Counter", style: "title" },
        { type: "text", text: String(count), style: "title" },
        { type: "row", children: [
            { type: "button", id: "minus", label: "−" },
            { type: "button", id: "plus", label: "+" },
            { type: "button", id: "reset", label: "Reset" }
        ] }
    ]);
}

draw();
while (true) {
    var event = mf.ui.wait();
    if (event.type !== "click") continue;
    if (event.id === "plus") count++;
    else if (event.id === "minus") count--;
    else if (event.id === "reset") count = 0;
    mf.storage.write("count.txt", String(count));
    draw();
}
