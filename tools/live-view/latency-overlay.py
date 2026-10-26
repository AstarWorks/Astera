#!/usr/bin/env python3
"""Integration measurement: draw a 32-bit epoch-ms barcode on the actual viewing X display.

The browser samples displayed video pixels and subtracts the decoded time from
Date.now(). This includes capture, encode, transport, buffering and decode.
"""
import time
import tkinter as tk

root = tk.Tk()
root.overrideredirect(True)
root.geometry('400x50+0+60')
root.attributes('-topmost', True)
canvas = tk.Canvas(root, width=400, height=50, highlightthickness=0, bg='black')
canvas.pack()
bits = [canvas.create_rectangle(i * 12, 0, i * 12 + 12, 50, outline='') for i in range(32)]
canvas.create_rectangle(384, 0, 400, 50, fill='#ff00ff', outline='')

def update():
    stamp = int(time.time() * 1000) & 0xffffffff
    for i, item in enumerate(bits):
        canvas.itemconfigure(item, fill='white' if stamp & (1 << (31 - i)) else 'black')
    root.after(50, update)

update()
root.mainloop()
