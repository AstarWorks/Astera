# per-frame tick stamp: 12 coloured block glyphs in a boss bar title. Cell 0 magenta, cell 1 cyan (self-calibrating
# start marker), cells 2..11 = game time mod 1024, LSB first, magenta 0 / cyan 1. tools/vfx-shot/framestamp.py decodes it.
execute store result score #g vfxstamp run time query gametime
scoreboard players operation #g vfxstamp %= #1024 vfxstamp
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p0 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c0 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c0 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p1 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c1 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c1 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p2 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c2 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c2 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p3 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c3 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c3 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p4 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c4 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c4 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p5 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c5 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c5 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p6 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c6 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c6 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p7 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c7 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c7 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p8 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c8 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c8 set value "#00ffff"
scoreboard players operation #t vfxstamp = #g vfxstamp
scoreboard players operation #t vfxstamp /= #p9 vfxstamp
scoreboard players operation #t vfxstamp %= #2 vfxstamp
execute if score #t vfxstamp matches 0 run data modify storage vfxstamp:s c9 set value "#ff00ff"
execute if score #t vfxstamp matches 1 run data modify storage vfxstamp:s c9 set value "#00ffff"
function vfxstamp:name with storage vfxstamp:s
bossbar set vfxstamp:tick players @a
