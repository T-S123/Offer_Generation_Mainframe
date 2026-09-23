<!-- Explains keyboard navigation, protected-field recovery and connection troubleshooting for the default c3270 console terminal. -->
# Terminal keyboard guide

These instructions apply to the console opened by `.\scripts\terminal.ps1`. Keep the separate `.\scripts\run.ps1` server window running, and maximize the terminal window so the application footer and status line are visible.

## Recover from X Protected

`X Protected` means that you tried to type on a label, a field boundary or another area that is not editable. The terminal locks input until you reset it; the message alone does not mean the application crashed.

1. Press **Ctrl+R** to reset the keyboard.
2. Press **Home** to move to the first editable field on the current screen.
3. Press **Tab** until you reach the field you want, then type.

On customer page 1, **Home** selects Display name and one **Tab** selects External ref. Reset and these navigation keys preserve your entered values. If replacing an existing value, use **Ctrl+U** after reaching its field, then type the replacement.

Use **Tab** between fields. Arrow keys move one screen cell at a time, including onto protected text and invisible field boundaries. Input fields have different starting columns and lengths, so **Down** does not reliably select the next input. Typing beyond a field's length can also cause a protection error.

## Keyboard reference

| Key | Action |
| --- | --- |
| **Tab** | Move to the next editable field; after the last field, wrap to the first. |
| **Ctrl+A**, release, then **Tab** | Move to the previous editable field. |
| **Home** | Move to the first editable field on this screen. |
| **Ctrl+R** | Reset a protected-field keyboard lock. |
| **Ctrl+U** | Clear the entire current editable field before replacing its value. |
| **Left / Right** | Move within a value; moving beyond its boundary can reach protected cells. |
| **Enter** | Submit the whole current screen or perform the action printed in its footer. |
| **F3** | Return or cancel, according to the footer; on customer forms it leaves the form without saving. |
| **F7 / F8** | Go back or page through results only where the footer advertises those actions. |
| **Ctrl+]**, then `Quit`, then **Enter** | Close c3270 and return to PowerShell. At the `c3270>` prompt, an empty Enter resumes the terminal instead. |

The emulator mappings come from the [c3270 keyboard reference](https://x3270.bgp.nu/Unix/c3270-man.html). The application decides what Enter and function keys do on each screen; read the footer before submitting. On a laptop, the keyboard may require **Fn+F3** or **Fn+F7** to send the function key instead of its media action.

**Do not use Ctrl+C to copy or unlock inside c3270.** It sends the terminal's Clear command, which makes this application redraw the screen and can discard edits not yet submitted. Ctrl+C is still the way to stop the application in its separate server PowerShell window.

## Fill in a customer form

1. At Select a Mode, type `01`, then press **Enter** for Customer.
2. At Customer Menu, type `01`, then press **Enter** for Enter my information.
3. Type `Alice Demo`, press **Tab**, type a new external reference such as `DEMO-CUST-002`, then press **Tab**.
4. Enter the remaining page 1 values, using **Tab** between fields. Use plain numbers such as `7000` for monthly income, without a dollar sign or thousands separator. The [demo walkthrough](demo-walkthrough.md#1-enter-a-synthetic-customer) lists all sample values.
5. When page 1 is complete, press **Enter once** to open page 2. Enter is not a way to move to the next field.
6. Complete page 2 with **Tab** between fields. Press **Enter once** to review. **F7** returns to page 1 if you need to edit it.
7. On the review screen, **Enter** saves and starts processing; **F7** returns to page 2. Wait for the response after submitting instead of repeatedly pressing Enter.

For Business forms, use the same field navigation. Enter may advance a form, save a draft or start a simulation depending on the displayed footer. Paste one field value at a time; avoid pasting an entire multiline form.

## Unexpected screens or an unresponsive session

- **Enter advanced the screen:** that is expected when the footer says Continue or Review. On other screens, Enter may save, refresh or select a menu item. Wait for each response before pressing it again.
- **Returned to a previous page/menu:** F7 goes back on customer page 2 and review; F3 cancels to the menu. Ordinary arrow keys should only move the cursor. An arrow key alone changing pages is not expected behavior.
- **X Protected:** reset with Ctrl+R, then use Home and Tab as described above. Repeated typing will not unlock it.
- **Disconnected or connection unavailable:** check that the server PowerShell window is still running. Restart the server if it stopped, then reconnect with `.\scripts\terminal.ps1`. Unsaved form data is not retained across a disconnected session.
- **Long pause:** a session closes after 15 minutes without traffic to the server. Typing locally in a 3270 field does not submit it; reconnect if the session has expired. A pending API request can also take time to return, so inspect the server window if the terminal remains busy after submission.

Customer entry pages do not automatically refresh or navigate backwards. If an ordinary arrow still changes pages, record the exact key, screen name and bottom status message; that needs investigation separately from a protected-field error.
