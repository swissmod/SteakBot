/flipbot cooldown <seconds> sets the minimum gap between /ah sell commands (default 3s). Raise it if your anti-spam still complains.
/flipbot delay <min> <max> sets the tick delay between actions (default 2-4).
/flipbot debug toggles step-by-step chat messages, which are on by default for now. If a step fails, the timeout message names it, like “Timed out on step: send /ah sell”.

Keep hotbar slot 9 (the last one) empty. Put a double chest of steaks within reach (about 4 blocks), and make sure your inventory has some free space in the main slots, not the hotbar.
Look at the chest and run /flipbot chest.
Set the price with /flipbot price <amount>, for example /flipbot price 5000.
Set the batch size with /flipbot count <n>. The default is 1, so you can skip this.
Start it with /flipbot start.
Stop it any time with /flipbot stop.
