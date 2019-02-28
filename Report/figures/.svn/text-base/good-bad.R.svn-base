plot.graph <- function(good=T) {

	pdf(ifelse(good, 'figures/good.pdf', 'figures/bad.pdf'), width=16, height=9)
	par(mar=c(6, 6, 1, 1))
	plot.new()
	plot.window(xlim=c(0, 5), ylim=c(-1.1,1.1))

	x <- seq(0, 5, by=.01)
	y <- sin(2*pi*x)

	lines(x, y, lwd=2)
	cex <- .6
	if (good) {
		cex <- 2.4
		title(xlab="time (s)",    cex.lab=cex)
		title(ylab="voltage (V)", cex.lab=cex)
	}
	else {
		cex <- 1
		title(xlab="time",    cex.lab=cex)
		title(ylab="voltage", cex.lab=cex)
	}
	axis(1,cex.axis=cex)
	axis(2,cex.axis=cex)
	box()
	dev.off()

}
plot.graph(T)
plot.graph(F)
