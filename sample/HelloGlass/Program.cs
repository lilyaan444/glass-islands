// Sample solution opened by `./gradlew runIde` to exercise the plugin in a sandboxed Rider.

using System;

var surfaces = new[] { "editor", "tool windows", "navigation bar", "status bar" };
foreach (var surface in surfaces)
{
    Console.WriteLine($"Liquid Glass over the {surface}");
}