BlockBlastBot v9

Изменения:
- одно размещение на один свежий кадр; после каждого drop бот заново распознаёт поле и все 3 фигуры;
- tray распознаётся отдельно по каждому из 3 слотов и не зависит от направления/яркости цвета;
- исправлен drop offset: фигура в Block Blast поднимается над пальцем, поэтому палец отпускается примерно на одну клетку ниже целевой клетки;
- поле 8x8;
- непрерывный Accessibility drag.

Для диагностики включите «Показывать касания».

## v13 changes
- Does not require all 3 tray pieces to be visible; continues with 1 or 2 pieces after a manual placement.
- Plans only one move from each fresh screenshot, then waits for the board/tray to update.
- Improved placement scoring so ties are not biased toward the top-left corner.
- Keeps the color-independent tray recognition and 8x8 board model.
